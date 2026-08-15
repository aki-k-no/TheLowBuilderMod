package dev.buildermod.mcp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EmbeddedMcpServerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EmbeddedMcpServer server;
    private PairingTokenStore tokenStore;

    @Before
    public void startServer() throws Exception {
        File tokenFile = new File(temporaryFolder.getRoot(), "pairing.token");
        tokenStore = new PairingTokenStore(tokenFile);
        server = new EmbeddedMcpServer(0, tokenStore);
        server.start();
    }

    @After
    public void stopServer() {
        server.stop();
    }

    @Test
    public void supportsLegacyInitializeAndObservationToolList() throws Exception {
        Response initialize = post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{" +
                "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," +
                "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
            null,
            null
        );
        Assert.assertEquals(200, initialize.status);
        Assert.assertTrue(initialize.body.contains("\"protocolVersion\":\"2025-11-25\""));

        Response list = post(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
            "2025-11-25",
            null
        );
        Assert.assertEquals(200, list.status);
        Assert.assertTrue(list.body.contains("\"name\":\"get_height_map\""));
        Assert.assertTrue(list.body.contains("\"name\":\"capture_view\""));
        Assert.assertTrue(list.body.contains("\"readOnlyHint\":true"));
    }

    @Test
    public void supportsModernDiscovery() throws Exception {
        Response response = post(
            "{\"jsonrpc\":\"2.0\",\"id\":\"d1\",\"method\":\"server/discover\",\"params\":{}}",
            "2026-07-28",
            "server/discover"
        );
        Assert.assertEquals(200, response.status);
        Assert.assertTrue(response.body.contains("2026-07-28"));
        Assert.assertTrue(response.body.contains("\"tools\":{}"));
    }

    @Test
    public void requiresThePairingToken() throws Exception {
        HttpURLConnection connection = open();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        write(connection, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        Assert.assertEquals(401, connection.getResponseCode());
        Assert.assertTrue(connection.getHeaderField("WWW-Authenticate").startsWith("Bearer"));
        connection.disconnect();
    }

    @Test
    public void servesSetupPageWithoutExposingTheToken() throws Exception {
        URL url = new URL("http://127.0.0.1:" + server.getBoundPort() + "/buildermod/setup");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        Assert.assertEquals(200, connection.getResponseCode());
        String body = read(connection.getInputStream());
        Assert.assertTrue(body.contains("Fully quit Codex"));
        Assert.assertFalse(body.contains(tokenStore.getToken()));
        connection.disconnect();
    }

    @Test
    public void rejectsRemoteBrowserOrigins() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        String request = "POST /mcp HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\n"
            + "Content-Type: application/json\r\n"
            + "Origin: https://attacker.example\r\n"
            + "Connection: close\r\n"
            + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n"
            + body;
        Socket socket = new Socket("127.0.0.1", server.getBoundPort());
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        String response = read(socket.getInputStream());
        socket.close();
        Assert.assertTrue(response.startsWith("HTTP/1.1 403"));
    }

    private Response post(String json, String protocolVersion, String method) throws Exception {
        HttpURLConnection connection = open();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setRequestProperty("Authorization", "Bearer " + tokenStore.getToken());
        if (protocolVersion != null) {
            connection.setRequestProperty("MCP-Protocol-Version", protocolVersion);
        }
        if (method != null) {
            connection.setRequestProperty("Mcp-Method", method);
        }
        connection.setDoOutput(true);
        write(connection, json);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : read(stream);
        connection.disconnect();
        return new Response(status, body);
    }

    private HttpURLConnection open() throws Exception {
        return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getBoundPort() + "/mcp").openConnection();
    }

    private void write(HttpURLConnection connection, String body) throws Exception {
        OutputStream output = connection.getOutputStream();
        output.write(body.getBytes(StandardCharsets.UTF_8));
        output.close();
    }

    private String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        input.close();
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
