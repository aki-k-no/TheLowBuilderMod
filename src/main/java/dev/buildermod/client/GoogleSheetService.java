package dev.buildermod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.buildermod.config.BuilderConfig;
import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Reads configured private Google spreadsheets using desktop OAuth and a read-only scope. */
public final class GoogleSheetService {
    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final BuilderConfig config;
    private final File credentialsFile;
    private final File tokenFile;
    private volatile boolean loginRunning;
    private String accessToken;
    private long accessTokenExpiresAt;

    public GoogleSheetService(BuilderConfig config) {
        this.config = config;
        File directory = config.getPairingTokenFile().getAbsoluteFile().getParentFile();
        this.credentialsFile = new File(directory, "buildermod-google-oauth.json");
        this.tokenFile = new File(directory, "buildermod-google-token.json");
    }

    public String setSheetUrl(String url) {
        return addSheet("default", url);
    }

    public String addSheet(String name, String url) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Sheet name must be 1-32 letters, digits, underscores, or hyphens");
        spreadsheetId(url);
        config.setGoogleSheet(name, url.trim());
        return "Google spreadsheet '" + name + "' configured.";
    }

    public String removeSheet(String name) {
        return config.removeGoogleSheet(name) ? "Google spreadsheet '" + name + "' removed." : "No spreadsheet named '" + name + "'.";
    }

    public String listNames() {
        Map<String, String> sheets = config.getGoogleSheets();
        return sheets.isEmpty() ? "No Google spreadsheets configured." : "Google spreadsheets: " + String.join(", ", sheets.keySet());
    }

    public Map<String, String> configuredSheets() { return config.getGoogleSheets(); }

    public boolean hasOAuthClient() { return credentialsFile.isFile(); }

    public synchronized String importOAuthClient(File source) throws IOException {
        if (source == null || !source.isFile() || source.length() > 64 * 1024L)
            throw new IOException("Choose a Google Desktop OAuth JSON smaller than 64 KiB");
        byte[] bytes = Files.readAllBytes(source.toPath());
        JsonObject root;
        try { root = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("Invalid Google OAuth JSON", error); }
        if (!root.has("installed") || !root.get("installed").isJsonObject()
            || string(root.getAsJsonObject("installed"), "client_id").isEmpty())
            throw new IOException("Expected a Google Desktop OAuth client JSON");
        String newClientId = string(root.getAsJsonObject("installed"), "client_id");
        String oldClientId = "";
        if (credentialsFile.isFile()) {
            try { oldClientId = loadClient().id; } catch (Exception ignored) { }
        }
        File parent = credentialsFile.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create Google config directory");
        File temporary = File.createTempFile("buildermod-google-oauth-", ".tmp", parent);
        try {
            Files.write(temporary.toPath(), bytes);
            restrictToOwner(temporary);
            Files.move(temporary.toPath(), credentialsFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            restrictToOwner(credentialsFile);
        } finally { temporary.delete(); }
        if (!newClientId.equals(oldClientId)) {
            Files.deleteIfExists(tokenFile.toPath());
            accessToken = null; accessTokenExpiresAt = 0L;
        }
        return "Google OAuth client imported. Click Sign in.";
    }

    public JsonObject list() {
        JsonArray names = new JsonArray();
        for (String name : config.getGoogleSheets().keySet()) names.add(new com.google.gson.JsonPrimitive(name));
        JsonObject data = new JsonObject(); data.add("names", names); data.addProperty("count", names.size());
        JsonObject item = new JsonObject(); item.addProperty("type", "text"); item.addProperty("text", data.toString());
        JsonArray content = new JsonArray(); content.add(item);
        JsonObject result = new JsonObject(); result.add("content", content); result.add("structuredContent", data);
        result.addProperty("isError", false); return result;
    }

    public String status() {
        return "Google Sheets: " + config.getGoogleSheets().size() + " configured"
            + ", " + (tokenFile.isFile() ? "OAuth token saved" : "not signed in")
            + (loginRunning ? ", login in progress" : "");
    }

    public synchronized boolean connect(final Consumer<String> completion) {
        if (loginRunning) return false;
        loginRunning = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try { login(); completion.accept("Google Sheets login completed."); }
                catch (Exception error) { completion.accept("Google Sheets login failed: " + safeMessage(error)); }
                finally { loginRunning = false; }
            }
        }, "BuilderMod-Google-OAuth");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void login() throws Exception {
        OAuthClient client = loadClient();
        final String state = randomUrlToken();
        final String verifier = randomUrlToken();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        final AtomicReference<String> code = new AtomicReference<String>();
        final AtomicReference<String> failure = new AtomicReference<String>();
        final CountDownLatch callback = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/buildermod/google/callback", new HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                boolean valid = "GET".equals(exchange.getRequestMethod()) && state.equals(query.get("state"));
                String message = valid ? "BuilderMod Google login received. You can close this tab." : "Invalid login response.";
                byte[] body = message.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
                if (valid) {
                    code.set(query.get("code")); failure.set(query.get("error")); callback.countDown();
                }
            }
        });
        server.start();
        try {
            String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/buildermod/google/callback";
            String authUrl = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=" + encode(client.id)
                + "&redirect_uri=" + encode(redirect) + "&scope=" + encode(SCOPE)
                + "&access_type=offline&prompt=" + encode("consent select_account") + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256";
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new IOException("Desktop browser is unavailable");
            Desktop.getDesktop().browse(new URI(authUrl));
            if (!callback.await(120, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for Google sign-in");
            if (failure.get() != null) throw new IOException("Google authorization: " + failure.get());
            if (code.get() == null) throw new IOException("Google did not return an authorization code");
            Map<String, String> body = new LinkedHashMap<String, String>();
            body.put("client_id", client.id); body.put("client_secret", client.secret);
            body.put("code", code.get()); body.put("code_verifier", verifier);
            body.put("grant_type", "authorization_code"); body.put("redirect_uri", redirect);
            saveTokens(postToken(body));
        } finally { server.stop(0); }
    }

    public JsonObject read(JsonObject arguments) throws Exception {
        Map<String, String> sheets = config.getGoogleSheets();
        String sheet = selectSheet(arguments, sheets);
        String id = spreadsheetId(sheets.get(sheet));
        String range = arguments.has("range") ? arguments.get("range").getAsString().trim() : "A1:Z200";
        if (range.isEmpty() || range.length() > 100 || range.matches("(?s).*[\\p{Cntrl}\\/\\\\?&#].*"))
            throw new IllegalArgumentException("range must be a short A1 notation without control or URL characters");
        String endpoint = "https://sheets.googleapis.com/v4/spreadsheets/" + id + "/values/"
            + encode(range).replace("+", "%20") + "?valueRenderOption=FORMATTED_VALUE";
        String token = accessToken();
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Google Sheets API returned HTTP " + status
                + (status == 401 ? "; reconnect with /builder sheet connect" : " (check the sheet URL, range, and access)"));
            JsonObject values = new JsonParser().parse(readLimited(connection.getInputStream())).getAsJsonObject();
            JsonArray rows = values.has("values") ? values.getAsJsonArray("values") : new JsonArray();
            JsonObject data = new JsonObject();
            data.addProperty("sheet", sheet);
            data.addProperty("range", values.has("range") ? values.get("range").getAsString() : range);
            data.addProperty("row_count", rows.size());
            data.add("values", rows);
            JsonObject item = new JsonObject(); item.addProperty("type", "text"); item.addProperty("text", data.toString());
            JsonArray content = new JsonArray(); content.add(item);
            JsonObject result = new JsonObject(); result.add("content", content);
            result.add("structuredContent", data); result.addProperty("isError", false);
            return result;
        } finally { connection.disconnect(); }
    }

    public JsonObject listTabs(JsonObject arguments) throws Exception {
        String sheet = selectSheet(arguments, config.getGoogleSheets());
        JsonObject data = metadata(sheet);
        return result(data);
    }

    public JsonObject readRows(JsonObject arguments) throws Exception {
        String sheet = selectSheet(arguments, config.getGoogleSheets());
        String tab = requiredString(arguments, "tab");
        JsonObject info = findTab(metadata(sheet), tab);
        int total = info.get("row_count").getAsInt();
        boolean headers = arguments.has("headers") && arguments.get("headers").getAsBoolean();
        int start = boundedInt(arguments, "start_row", headers ? 2 : 1, 1, Math.max(2, total + 1));
        int limit = boundedInt(arguments, "limit", 30, 1, 200);
        int maxChars = boundedInt(arguments, "max_chars", 0, 0, 10000);
        String columns = optionalString(arguments, "columns", "").trim();
        if (!columns.isEmpty() && !columns.matches("(?i)[A-Z]{1,3}(?::[A-Z]{1,3})?"))
            throw new IllegalArgumentException("columns must be a column or span such as C or A:D");
        String[] span = columns.isEmpty() ? new String[] {"A", columnName(Math.min(702, Math.max(1, info.get("column_count").getAsInt())))} : columns.toUpperCase().split(":", 2);
        String first = span[0], last = span.length == 2 ? span[1] : first;
        if (columnIndex(first) > columnIndex(last)) throw new IllegalArgumentException("columns are reversed");
        JsonArray header = headers ? fetchRows(sheet, tab, 1, 1, first, last) : new JsonArray();
        JsonArray rows = new JsonArray();
        int count = Math.min(limit, Math.max(0, total - start + 1));
        while (count > 0) {
            try {
                rows = fetchRows(sheet, tab, start, start + count - 1, first, last);
                break;
            } catch (ResponseTooLarge error) {
                if (count == 1) throw new IOException("One sheet row exceeds 256 KiB; select fewer columns");
                count = Math.max(1, count / 2);
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("sheet", sheet); data.addProperty("tab", tab);
        data.addProperty("start_row", start); data.addProperty("row_count", rows.size());
        data.addProperty("columns", first + ":" + last);
        if (headers) data.add("headers", header.size() == 0 ? new JsonArray() : header.get(0).getAsJsonArray());
        if (headers) {
            JsonArray records = new JsonArray();
            JsonArray names = data.getAsJsonArray("headers");
            for (JsonElement row : rows) {
                JsonObject record = new JsonObject(); JsonArray cells = row.getAsJsonArray();
                for (int i = 0; i < names.size() && i < cells.size(); i++) {
                    String name = names.get(i).getAsString();
                    if (!name.isEmpty()) record.addProperty(name, truncate(cells.get(i).getAsString(), maxChars));
                }
                records.add(record);
            }
            data.add("records", records);
        } else {
            JsonArray trimmed = new JsonArray();
            for (JsonElement row : rows) {
                JsonArray cells = new JsonArray();
                for (JsonElement cell : row.getAsJsonArray()) cells.add(new com.google.gson.JsonPrimitive(truncate(cell.getAsString(), maxChars)));
                trimmed.add(cells);
            }
            data.add("values", trimmed);
        }
        JsonArray outputRows = data.getAsJsonArray(headers ? "records" : "values");
        int originalOutputSize = outputRows.size();
        while (data.toString().getBytes(StandardCharsets.UTF_8).length > 96 * 1024 && outputRows.size() > 1) {
            JsonArray smaller = new JsonArray();
            for (int i = 0; i < outputRows.size() - 1; i++) smaller.add(outputRows.get(i));
            outputRows = smaller;
            data.add(headers ? "records" : "values", outputRows);
        }
        if (data.toString().getBytes(StandardCharsets.UTF_8).length > 96 * 1024)
            throw new IOException("One output row is too large; select fewer columns or set max_chars");
        data.addProperty("row_count", outputRows.size());
        int advanced = outputRows.size() == originalOutputSize ? count : outputRows.size();
        if (start + advanced <= total) data.addProperty("next_row", start + advanced);
        return result(data);
    }

    public JsonObject search(JsonObject arguments) throws Exception {
        String sheet = selectSheet(arguments, config.getGoogleSheets());
        String query = requiredString(arguments, "query");
        if (query.length() > 200) throw new IllegalArgumentException("query is too long");
        boolean regex = arguments.has("regex") && arguments.get("regex").getAsBoolean();
        Pattern pattern = regex ? Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
        JsonObject meta = metadata(sheet);
        String tab = optionalString(arguments, "tab", "");
        int start = boundedInt(arguments, "start_row", 1, 1, 10000000);
        int maxRows = boundedInt(arguments, "max_scan_rows", 200, 1, 1000);
        int maxHits = boundedInt(arguments, "max_hits", 30, 1, 100);
        int startColumn = boundedInt(arguments, "start_column", 1, 1, 702);
        JsonArray tabs = meta.getAsJsonArray("tabs");
        JsonArray hits = new JsonArray();
        int scanned = 0; String nextTab = null; int nextRow = 0; int nextColumn = 1;
        String startTab = optionalString(arguments, "start_tab", "");
        boolean reachedStart = startTab.isEmpty();
        for (JsonElement element : tabs) {
            JsonObject info = element.getAsJsonObject(); String name = info.get("title").getAsString();
            if (!tab.isEmpty() && !tab.equals(name)) continue;
            if (!reachedStart) { if (!startTab.equals(name)) continue; reachedStart = true; }
            int rows = info.get("row_count").getAsInt();
            int columns = Math.min(702, info.get("column_count").getAsInt());
            for (int row = start; row <= rows;) {
                int batch = Math.min(25, Math.min(rows - row + 1, maxRows - scanned));
                if (batch <= 0) { nextTab = name; nextRow = row; break; }
                JsonArray values;
                while (true) {
                    try { values = fetchRows(sheet, name, row, row + batch - 1, "A", columnName(Math.max(1, columns))); break; }
                    catch (ResponseTooLarge error) {
                        if (batch == 1) throw new IOException("A sheet row exceeds 256 KiB; narrow the sheet");
                        batch = Math.max(1, batch / 2);
                    }
                }
                for (int i = 0; i < values.size(); i++) {
                    JsonArray cells = values.get(i).getAsJsonArray();
                    for (int c = row + i == start && name.equals(startTab.isEmpty() ? name : startTab) ? startColumn - 1 : 0; c < cells.size(); c++) {
                        String value = cells.get(c).getAsString();
                        if (regex ? pattern.matcher(value).find() : value.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) {
                            JsonObject hit = new JsonObject(); hit.addProperty("tab", name);
                            hit.addProperty("cell", columnName(c + 1) + (row + i));
                            hit.addProperty("preview", truncate(value, 120)); hits.add(hit);
                            if (hits.size() >= maxHits) {
                                nextTab = name; nextRow = row + i;
                                nextColumn = c + 2;
                                if (nextColumn > columns) { nextRow++; nextColumn = 1; }
                                break;
                            }
                        }
                    }
                    if (hits.size() >= maxHits) break;
                }
                if (hits.size() >= maxHits) break;
                row += batch; scanned += batch;
            }
            if (nextTab != null) break;
            start = 1;
        }
        if (!tab.isEmpty()) findTab(meta, tab);
        JsonObject data = new JsonObject(); data.addProperty("sheet", sheet);
        data.add("hits", hits); data.addProperty("scanned_rows", scanned);
        if (nextTab != null) {
            data.addProperty("next_tab", nextTab); data.addProperty("next_row", nextRow);
            data.addProperty("next_column", nextColumn);
        }
        return result(data);
    }

    public JsonObject getFunction(JsonObject arguments) throws Exception {
        String sheet = selectSheet(arguments, config.getGoogleSheets());
        String group = requiredString(arguments, "groupid"), function = requiredString(arguments, "funcname");
        String tab = optionalString(arguments, "tab", "function");
        JsonObject info = findTab(metadata(sheet), tab);
        int columns = Math.min(702, Math.max(1, info.get("column_count").getAsInt()));
        String last = columnName(columns);
        JsonArray headerRows = fetchRows(sheet, tab, 1, 1, "A", last);
        if (headerRows.size() == 0) throw new IOException("Function tab has no header row");
        JsonArray headers = headerRows.get(0).getAsJsonArray();
        int groupColumn = -1, functionColumn = -1;
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.get(i).getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            if ("groupid".equals(name)) groupColumn = i;
            if ("funcname".equals(name)) functionColumn = i;
        }
        if (groupColumn < 0 || functionColumn < 0) throw new IOException("Function tab needs groupid and funcname headers");
        for (int row = 2, total = info.get("row_count").getAsInt(); row <= total;) {
            int batch = Math.min(25, total - row + 1);
            JsonArray values;
            while (true) {
                try { values = fetchRows(sheet, tab, row, row + batch - 1, "A", last); break; }
                catch (ResponseTooLarge error) {
                    if (batch == 1) throw new IOException("A function row exceeds 256 KiB");
                    batch = Math.max(1, batch / 2);
                }
            }
            for (int i = 0; i < values.size(); i++) {
                JsonArray cells = values.get(i).getAsJsonArray();
                if (cells.size() <= Math.max(groupColumn, functionColumn)) continue;
                if (group.equals(cells.get(groupColumn).getAsString()) && function.equals(cells.get(functionColumn).getAsString())) {
                    JsonObject data = new JsonObject(); data.addProperty("sheet", sheet); data.addProperty("tab", tab); data.addProperty("row", row + i);
                    for (int c = 0; c < headers.size() && c < cells.size(); c++) {
                        String key = headers.get(c).getAsString(); if (!key.isEmpty()) data.add(key, cells.get(c));
                    }
                    return result(data);
                }
            }
            row += batch;
        }
        throw new IOException("Function not found: " + group + "/" + function);
    }

    private JsonObject metadata(String sheet) throws Exception {
        String id = spreadsheetId(config.getGoogleSheets().get(sheet));
        JsonObject response = apiGet("https://sheets.googleapis.com/v4/spreadsheets/" + id
            + "?fields=properties(title),sheets(properties(title,gridProperties(rowCount,columnCount)))");
        JsonObject data = new JsonObject(); data.addProperty("sheet", sheet);
        data.addProperty("title", response.getAsJsonObject("properties").get("title").getAsString());
        JsonArray tabs = new JsonArray();
        if (response.has("sheets")) for (JsonElement element : response.getAsJsonArray("sheets")) {
            JsonObject properties = element.getAsJsonObject().getAsJsonObject("properties");
            JsonObject grid = properties.getAsJsonObject("gridProperties");
            JsonObject item = new JsonObject(); item.addProperty("title", properties.get("title").getAsString());
            item.addProperty("row_count", grid == null || !grid.has("rowCount") ? 0 : grid.get("rowCount").getAsInt());
            item.addProperty("column_count", grid == null || !grid.has("columnCount") ? 0 : grid.get("columnCount").getAsInt());
            tabs.add(item);
        }
        data.add("tabs", tabs); return data;
    }

    private JsonArray fetchRows(String sheet, String tab, int firstRow, int lastRow, String firstCol, String lastCol) throws Exception {
        String id = spreadsheetId(config.getGoogleSheets().get(sheet));
        String range = "'" + tab.replace("'", "''") + "'!" + firstCol + firstRow + ":" + lastCol + lastRow;
        JsonObject response = apiGet("https://sheets.googleapis.com/v4/spreadsheets/" + id + "/values/"
            + encode(range).replace("+", "%20") + "?valueRenderOption=FORMATTED_VALUE");
        return response.has("values") ? response.getAsJsonArray("values") : new JsonArray();
    }

    private JsonObject apiGet(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken());
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Google Sheets API returned HTTP " + status
                + (status == 401 ? "; reconnect with /builder sheet connect" : " (check tab name and access)"));
            return new JsonParser().parse(readLimited(connection.getInputStream())).getAsJsonObject();
        } finally { connection.disconnect(); }
    }

    private static JsonObject findTab(JsonObject metadata, String title) {
        for (JsonElement element : metadata.getAsJsonArray("tabs")) {
            JsonObject tab = element.getAsJsonObject();
            if (title.equals(tab.get("title").getAsString())) return tab;
        }
        throw new IllegalArgumentException("Unknown tab '" + title + "'; call list_tabs");
    }

    private static JsonObject result(JsonObject data) {
        JsonObject item = new JsonObject(); item.addProperty("type", "text"); item.addProperty("text", data.toString());
        JsonArray content = new JsonArray(); content.add(item);
        JsonObject result = new JsonObject(); result.add("content", content); result.add("structuredContent", data);
        result.addProperty("isError", false); return result;
    }

    private static String requiredString(JsonObject args, String key) {
        String value = optionalString(args, key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String optionalString(JsonObject args, String key, String fallback) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : fallback;
    }

    private static int boundedInt(JsonObject args, String key, int fallback, int min, int max) {
        int value = args.has(key) ? args.get(key).getAsInt() : fallback;
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }

    private static String truncate(String value, int max) { return max > 0 && value.length() > max ? value.substring(0, max) : value; }

    private static String columnName(int oneBased) {
        StringBuilder name = new StringBuilder();
        for (int n = oneBased; n > 0; n = (n - 1) / 26) name.insert(0, (char) ('A' + (n - 1) % 26));
        return name.toString();
    }

    private static int columnIndex(String name) {
        int value = 0;
        for (int i = 0; i < name.length(); i++) value = value * 26 + (name.charAt(i) - 'A' + 1);
        return value;
    }

    private synchronized String accessToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - 60000L) return accessToken;
        if (!tokenFile.isFile()) throw new IOException("Google Sheets is not signed in; run /builder sheet connect");
        JsonObject saved = new JsonParser().parse(new String(Files.readAllBytes(tokenFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        String refresh = string(saved, "refresh_token");
        if (refresh.isEmpty()) throw new IOException("Google refresh token is missing; run /builder sheet connect");
        OAuthClient client = loadClient();
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("client_id", client.id); body.put("client_secret", client.secret);
        body.put("refresh_token", refresh); body.put("grant_type", "refresh_token");
        JsonObject response = postToken(body);
        accessToken = string(response, "access_token");
        accessTokenExpiresAt = System.currentTimeMillis() + response.get("expires_in").getAsLong() * 1000L;
        return accessToken;
    }

    private synchronized void saveTokens(JsonObject response) throws IOException {
        String refresh = string(response, "refresh_token");
        if (refresh.isEmpty()) throw new IOException("Google did not provide a refresh token");
        JsonObject saved = new JsonObject(); saved.addProperty("refresh_token", refresh);
        File parent = tokenFile.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create Google token directory");
        File temporary = File.createTempFile("buildermod-google-", ".tmp", parent);
        try {
            Files.write(temporary.toPath(), saved.toString().getBytes(StandardCharsets.UTF_8));
            restrictToOwner(temporary);
            Files.move(temporary.toPath(), tokenFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            restrictToOwner(tokenFile);
        } finally { temporary.delete(); }
        accessToken = string(response, "access_token");
        accessTokenExpiresAt = System.currentTimeMillis() + response.get("expires_in").getAsLong() * 1000L;
    }

    private OAuthClient loadClient() throws IOException {
        if (!credentialsFile.isFile()) throw new IOException("Place your Google Desktop OAuth JSON at " + credentialsFile.getAbsolutePath());
        JsonObject root = new JsonParser().parse(new String(Files.readAllBytes(credentialsFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has("installed") || !root.get("installed").isJsonObject()) throw new IOException("Expected a Google Desktop OAuth client JSON");
        JsonObject installed = root.getAsJsonObject("installed");
        String id = string(installed, "client_id"), secret = string(installed, "client_secret");
        if (id.isEmpty()) throw new IOException("Google OAuth client_id is missing");
        return new OAuthClient(id, secret);
    }

    private static JsonObject postToken(Map<String, String> fields) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://oauth2.googleapis.com/token").openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setConnectTimeout(8000); connection.setReadTimeout(12000);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (form.length() > 0) form.append('&');
            form.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        try {
            byte[] bytes = form.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            if (connection.getResponseCode() != 200)
                throw new IOException("Google OAuth token endpoint returned HTTP " + connection.getResponseCode());
            return new JsonParser().parse(readLimited(connection.getInputStream())).getAsJsonObject();
        } finally { connection.disconnect(); }
    }

    static String spreadsheetId(String url) {
        if (url == null) throw new IllegalArgumentException("Set a Google spreadsheet URL with /builder sheet set <url>");
        String value = url.trim();
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"docs.google.com".equalsIgnoreCase(uri.getHost()))
                throw new IllegalArgumentException("Only Google Sheets URLs on docs.google.com are accepted");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^/spreadsheets/d/([A-Za-z0-9_-]+)(?:/.*)?$").matcher(uri.getPath());
            if (!matcher.matches()) throw new IllegalArgumentException("Invalid Google spreadsheet URL");
            return matcher.group(1);
        } catch (java.net.URISyntaxException error) { throw new IllegalArgumentException("Invalid Google spreadsheet URL"); }
    }

    static String selectSheet(JsonObject arguments, Map<String, String> sheets) {
        if (sheets.isEmpty()) throw new IllegalArgumentException("No Google spreadsheets configured; run /builder sheet add <name> <url>");
        if (arguments.has("sheet")) {
            JsonElement value = arguments.get("sheet");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("sheet must be a registered name");
            String name = value.getAsString();
            if (!sheets.containsKey(name)) throw new IllegalArgumentException("Unknown sheet '" + name + "'; call list_config_sheets");
            return name;
        }
        if (sheets.size() == 1) return sheets.keySet().iterator().next();
        throw new IllegalArgumentException("Multiple spreadsheets are configured; specify sheet or call list_config_sheets");
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new ResponseTooLarge();
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (raw == null) return result;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            try { result.put(URLDecoder.decode(pair[0], "UTF-8"), pair.length == 2 ? URLDecoder.decode(pair[1], "UTF-8") : ""); }
            catch (Exception ignored) { }
        }
        return result;
    }

    private static String randomUrlToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception error) { throw new IllegalArgumentException("Could not encode URL value"); }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void restrictToOwner(File file) {
        file.setReadable(false, false); file.setWritable(false, false);
        file.setReadable(true, true); file.setWritable(true, true);
    }

    private static final class OAuthClient {
        private final String id, secret;
        private OAuthClient(String id, String secret) { this.id = id; this.secret = secret; }
    }

    private static final class ResponseTooLarge extends IOException {
        private ResponseTooLarge() { super("Google Sheets response exceeds 256 KiB; request a smaller range"); }
    }
}
