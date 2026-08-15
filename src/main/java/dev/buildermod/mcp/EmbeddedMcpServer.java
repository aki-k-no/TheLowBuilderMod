package dev.buildermod.mcp;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class EmbeddedMcpServer {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final String ENDPOINT_PATH = "/mcp";
    private static final String SETUP_PATH = "/buildermod/setup";
    private static final String STATUS_PATH = "/buildermod/status";

    private final int requestedPort;
    private final PairingTokenStore tokenStore;
    private final Gson gson = new Gson();
    private final McpJsonRpcRouter router;
    private HttpServer httpServer;
    private ExecutorService executor;
    private int boundPort;
    private volatile long lastAuthenticatedAt;

    EmbeddedMcpServer(int port, PairingTokenStore tokenStore) {
        this(port, tokenStore, new McpJsonRpcRouter());
    }

    EmbeddedMcpServer(int port, PairingTokenStore tokenStore, McpToolExecutor toolExecutor) {
        this(port, tokenStore, new McpJsonRpcRouter(toolExecutor));
    }

    private EmbeddedMcpServer(int port, PairingTokenStore tokenStore, McpJsonRpcRouter router) {
        this.requestedPort = port;
        this.tokenStore = tokenStore;
        this.router = router;
    }

    synchronized void start() throws IOException {
        if (httpServer != null) {
            return;
        }

        final HttpServer created = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort),
            0
        );
        executor = Executors.newFixedThreadPool(2, new DaemonThreadFactory());
        created.setExecutor(executor);
        created.createContext(ENDPOINT_PATH, new McpHandler());
        created.createContext(SETUP_PATH, new SetupHandler());
        created.createContext(STATUS_PATH, new StatusHandler());

        // HttpServer's dispatcher inherits the daemon flag from the thread calling start().
        Thread starter = new Thread(new Runnable() {
            @Override
            public void run() {
                created.start();
            }
        }, "BuilderMod-MCP-Starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            created.stop(0);
            executor.shutdownNow();
            throw new IOException("Interrupted while starting MCP server", exception);
        }

        httpServer = created;
        boundPort = created.getAddress().getPort();
    }

    synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    synchronized String getEndpoint() {
        return "http://127.0.0.1:" + boundPort + ENDPOINT_PATH;
    }

    synchronized int getBoundPort() {
        return boundPort;
    }

    synchronized String getSetupUrl() {
        return "http://127.0.0.1:" + boundPort + SETUP_PATH;
    }

    long getLastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    private final class McpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!ENDPOINT_PATH.equals(exchange.getRequestURI().getPath())) {
                    sendEmpty(exchange, 404);
                    return;
                }
                if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                    sendEmpty(exchange, 403);
                    return;
                }
                if (!isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"BuilderMod\"");
                    sendEmpty(exchange, 401);
                    return;
                }
                lastAuthenticatedAt = System.currentTimeMillis();
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST, GET");
                    sendEmpty(exchange, 405);
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST, GET");
                    sendEmpty(exchange, 405);
                    return;
                }

                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    sendEmpty(exchange, 415);
                    return;
                }

                String requestBody;
                try {
                    requestBody = readBody(exchange.getRequestBody());
                } catch (RequestTooLargeException exception) {
                    sendEmpty(exchange, 413);
                    return;
                }

                McpJsonRpcRouter.RouteResult result = router.route(
                    requestBody,
                    exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"),
                    exchange.getRequestHeaders().getFirst("Mcp-Method")
                );
                if (result.getBody() == null) {
                    sendEmpty(exchange, result.getStatusCode());
                } else {
                    sendJson(exchange, result.getStatusCode(), gson.toJson(result.getBody()));
                }
            } finally {
                exchange.close();
            }
        }
    }

    private final class SetupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!SETUP_PATH.equals(exchange.getRequestURI().getPath())) {
                    sendEmpty(exchange, 404);
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendEmpty(exchange, 405);
                    return;
                }
                sendHtml(exchange, setupPage());
            } finally {
                exchange.close();
            }
        }
    }

    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!STATUS_PATH.equals(exchange.getRequestURI().getPath())) {
                    sendEmpty(exchange, 404);
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendEmpty(exchange, 405);
                    return;
                }
                sendJson(exchange, 200, "{\"connected\":" + (lastAuthenticatedAt > 0L) + "}");
            } finally {
                exchange.close();
            }
        }
    }

    private String readBody(InputStream input) throws IOException, RequestTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isAuthorized(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return tokenStore.matches(authorization.substring(7).trim());
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        OutputStream response = exchange.getResponseBody();
        response.write(body);
        response.close();
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        OutputStream response = exchange.getResponseBody();
        response.write(body);
        response.close();
    }

    private String setupPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>BuilderMod Codex Setup</title><style>"
            + "body{font-family:system-ui,sans-serif;max-width:720px;margin:48px auto;padding:0 20px;line-height:1.6}"
            + ".card{border:1px solid #ccc;border-radius:12px;padding:24px}"
            + ".waiting{color:#a15c00}.connected{color:#087a36;font-weight:700}code{background:#eee;padding:2px 6px;border-radius:4px}"
            + "</style></head><body><div class=\"card\"><h1>BuilderMod is installed</h1>"
            + "<p id=\"status\" class=\"waiting\">Waiting for Codex...</p>"
            + "<ol><li>Fully quit Codex from the system tray. Closing only the window is not enough.</li>"
            + "<li>Open Codex again.</li><li>Open <code>/mcp</code> in Codex and check BuilderMod.</li></ol>"
            + "<p>This page contains no pairing token and can remain open while Codex restarts.</p></div>"
            + "<script>async function poll(){try{const r=await fetch('/buildermod/status',{cache:'no-store'});"
            + "const s=await r.json();if(s.connected){const e=document.getElementById('status');"
            + "e.textContent='Codex connected successfully.';e.className='connected';return;}}catch(e){}setTimeout(poll,1000)}poll();</script>"
            + "</body></html>";
    }

    private void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, -1);
    }

    private static final class RequestTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "BuilderMod-MCP-Worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
