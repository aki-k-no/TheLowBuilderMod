package dev.buildermod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Read-only, size-limited reader for the public TheLow development wiki. */
final class TheLowWikiReader {
    private static final String ROOT = "https://wikiwiki.jp/thelow-dev/";
    private static final int MAX_BYTES = 300 * 1024;
    private TheLowWikiReader() { }

    static JsonObject read(JsonObject arguments) throws Exception {
        String page = arguments.has("page") ? arguments.get("page").getAsString().trim() : "";
        String url = urlFor(page);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "BuilderMod/0.12.7 Wiki reader");
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IllegalArgumentException("TheLow wiki page exceeds 300 KiB");
                output.write(buffer, 0, count);
            }
            String text = htmlToText(new String(output.toByteArray(), StandardCharsets.UTF_8));
            JsonObject result = new JsonObject(); JsonArray content = new JsonArray(); JsonObject item = new JsonObject();
            item.addProperty("type", "text"); item.addProperty("text", text); content.add(item); result.add("content", content);
            JsonObject data = new JsonObject(); data.addProperty("source_url", url); data.addProperty("page", page.isEmpty() ? "top page" : page); data.addProperty("text", text);
            result.add("structuredContent", data); result.addProperty("isError", false); return result;
        } finally { connection.disconnect(); }
    }

    static String urlFor(String page) {
        if (page == null || page.trim().isEmpty()) return ROOT;
        String clean = page.trim();
        if (clean.contains(":") || clean.contains("..") || clean.contains("\\")) throw new IllegalArgumentException("page must be a relative TheLow wiki page name");
        try { return ROOT + URLEncoder.encode(clean, "UTF-8").replace("+", "%20"); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid wiki page name"); }
    }

    static String htmlToText(String html) {
        String value = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ").replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        value = value.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n").replaceAll("(?s)<[^>]+>", " ");
        value = value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        value = value.replaceAll("[ \t]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return value.length() > 100000 ? value.substring(0, 100000) + "\n[truncated]" : value;
    }
}
