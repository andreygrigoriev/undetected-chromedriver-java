package io.github.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.andreygrigoriev.uc.support.StubHttpServer;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CdpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String address(StubHttpServer server) {
        return server.baseUrl().substring("http://".length());
    }

    private static StubHttpServer serverWithTargets(String wsUrl) {
        return new StubHttpServer().respond("/json", 200,
                "[{\"id\":\"T1\",\"type\":\"page\",\"webSocketDebuggerUrl\":\"" + wsUrl + "\"}]");
    }

    @Test
    void constructorReadsFirstTarget() {
        try (StubHttpServer server = serverWithTargets("ws://127.0.0.1:1/devtools/page/T1")) {
            Cdp cdp = new Cdp(address(server));

            assertThat(cdp.targetId()).isEqualTo("T1");
            assertThat(cdp.webSocketUrl()).isEqualTo("ws://127.0.0.1:1/devtools/page/T1");
            assertThat(cdp.lastJson().isArray()).isTrue();
        }
    }

    @Test
    void tabListReturnsParsedJson() {
        try (StubHttpServer server = serverWithTargets("ws://x").respond("/json/list", 200, "[{\"id\":\"A\"}]")) {
            Cdp cdp = new Cdp(address(server));

            assertThat(cdp.tabList().path(0).path("id").asText()).isEqualTo("A");
            assertThat(cdp.lastJson().path(0).path("id").asText()).isEqualTo("A");
        }
    }

    @Test
    void tabNewUsesPut() {
        try (StubHttpServer server = serverWithTargets("ws://x").respond("/json/new", 200, "{\"id\":\"N\"}")) {
            Cdp cdp = new Cdp(address(server));

            JsonNode created = cdp.tabNew("https://example.com");

            assertThat(created.path("id").asText()).isEqualTo("N");
            assertThat(server.requests()).contains(new StubHttpServer.Request("PUT", "/json/new"));
        }
    }

    @Test
    void tabActivateAndCloseHitTheirEndpoints() {
        try (StubHttpServer server = serverWithTargets("ws://x")
                .respond("/json/activate/A", 200, "Target activated")
                .respond("/json/close/A", 200, "Target is closing")) {
            Cdp cdp = new Cdp(address(server));

            assertThat(cdp.tabActivate("A")).isNull();
            assertThat(cdp.tabClose("A")).isNull();
            assertThat(server.requests()).contains(
                    new StubHttpServer.Request("GET", "/json/activate/A"),
                    new StubHttpServer.Request("GET", "/json/close/A"));
        }
    }

    @Test
    void tabCloseLastOpenedClosesLastPageTarget() {
        String tabs = "[{\"id\":\"P1\",\"type\":\"page\"},{\"id\":\"P2\",\"type\":\"page\"},"
                + "{\"id\":\"W1\",\"type\":\"service_worker\"}]";
        try (StubHttpServer server = serverWithTargets("ws://x")
                .respond("/json/list", 200, tabs)
                .respond("/json/close/P2", 200, "Target is closing")) {
            Cdp cdp = new Cdp(address(server));

            cdp.tabCloseLastOpened();

            assertThat(server.requests()).contains(new StubHttpServer.Request("GET", "/json/close/P2"));
            assertThat(server.requests()).doesNotContain(new StubHttpServer.Request("GET", "/json/close/W1"));
        }
    }

    @Test
    void tabCloseLastOpenedWithoutPagesDoesNothing() {
        try (StubHttpServer server = serverWithTargets("ws://x").respond("/json/list", 200, "[]")) {
            Cdp cdp = new Cdp(address(server));

            assertThat(cdp.tabCloseLastOpened()).isNull();
            assertThat(server.requests()).noneMatch(r -> r.path().startsWith("/json/close"));
        }
    }

    @Test
    void unparseableJsonGivesNull() {
        try (StubHttpServer server = new StubHttpServer().respond("/json", 200, "not json")) {
            Cdp cdp = new Cdp(address(server));

            assertThat(cdp.lastJson()).isNull();
            assertThat(cdp.targetId()).isNull();
            assertThatThrownBy(() -> cdp.send("Browser.getVersion", Map.of()).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void sendSkipsEventsAndReturnsMatchingResult() throws Exception {
        try (StubWebSocketServer ws = new StubWebSocketServer(true);
                StubHttpServer http = serverWithTargets(ws.url())) {
            Cdp cdp = new Cdp(address(http));

            JsonNode result = cdp.send("Browser.getVersion", Map.of("a", 1)).get(5, TimeUnit.SECONDS);

            assertThat(result.path("product").asText()).isEqualTo("Chrome/1");
            assertThat(JSON.readTree(ws.received()).path("method").asText()).isEqualTo("Browser.getVersion");
            assertThat(JSON.readTree(ws.received()).path("params").path("a").asInt()).isEqualTo(1);
        }
    }

    @Test
    void sendTimesOutWithoutReply() throws Exception {
        try (StubWebSocketServer ws = new StubWebSocketServer(false);
                StubHttpServer http = serverWithTargets(ws.url())) {
            Cdp cdp = new Cdp(address(http));

            assertThatThrownBy(() -> cdp.send("Browser.getVersion", Map.of()).get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        }
    }

    /** One-connection WebSocket server: reads a command, optionally answers after an unrelated event. */
    private static final class StubWebSocketServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private volatile String received;

        StubWebSocketServer(boolean reply) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = Thread.ofVirtual().start(() -> serve(reply));
        }

        String url() {
            return "ws://127.0.0.1:" + server.getLocalPort() + "/devtools/page/T1";
        }

        String received() {
            return received;
        }

        private void serve(boolean reply) {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                String key = null;
                String line;
                while (!(line = readLine(in)).isEmpty()) {
                    if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                        key = line.substring(line.indexOf(':') + 1).trim();
                    }
                }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();

                received = readFrame(new DataInputStream(in));
                if (reply) {
                    int id = JSON.readTree(received).path("id").asInt();
                    writeFrame(out, "{\"method\":\"Page.loadEventFired\",\"params\":{}}");
                    writeFrame(out, "{\"id\":" + (id + 100) + ",\"result\":{\"product\":\"wrong\"}}");
                    writeFrame(out, "{\"id\":" + id + ",\"result\":{\"product\":\"Chrome/1\"}}");
                }
                in.read();
            } catch (Exception ignored) {
                // server closed or client gone
            }
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                if (c != '\r') {
                    sb.append((char) c);
                }
            }
            return sb.toString();
        }

        private static String readFrame(DataInputStream in) throws IOException {
            in.readByte();
            int len = in.readByte() & 0x7f;
            if (len == 126) {
                len = in.readUnsignedShort();
            } else if (len == 127) {
                len = (int) in.readLong();
            }
            byte[] mask = new byte[4];
            in.readFully(mask);
            byte[] payload = new byte[len];
            in.readFully(payload);
            for (int i = 0; i < len; i++) {
                payload[i] ^= mask[i % 4];
            }
            return new String(payload, StandardCharsets.UTF_8);
        }

        private static void writeFrame(OutputStream out, String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            out.write(0x81);
            if (payload.length < 126) {
                out.write(payload.length);
            } else {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length & 0xff);
            }
            out.write(payload);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            thread.interrupt();
        }
    }
}
