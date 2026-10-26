package io.andreygrigoriev.uc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Talks to Chrome's DevTools endpoints: the HTTP {@code /json/*} API and one-shot WebSocket commands. */
public class Cdp {

    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String debuggerAddress;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicInteger ids = new AtomicInteger();
    private final String targetId;
    private final String webSocketUrl;
    private volatile JsonNode lastJson;

    public Cdp(String debuggerAddress) {
        this.debuggerAddress = debuggerAddress;
        JsonNode targets = request("GET", "/json");
        JsonNode first = targets != null && targets.isArray() ? targets.path(0) : null;
        this.targetId = first == null ? null : textOrNull(first.path("id"));
        this.webSocketUrl = first == null ? null : textOrNull(first.path("webSocketDebuggerUrl"));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    public String targetId() {
        return targetId;
    }

    public String webSocketUrl() {
        return webSocketUrl;
    }

    public JsonNode tabList() {
        return request("GET", "/json/list");
    }

    /** Opens a new tab. Chrome 111+ only accepts PUT here. */
    public JsonNode tabNew(String url) {
        return request("PUT", "/json/new?" + url);
    }

    public JsonNode tabActivate(String id) {
        return request("GET", "/json/activate/" + id);
    }

    public JsonNode tabClose(String id) {
        return request("GET", "/json/close/" + id);
    }

    /** Closes the last page target in the tab list, if any. */
    public JsonNode tabCloseLastOpened() {
        JsonNode tabs = tabList();
        if (tabs == null || !tabs.isArray()) {
            return null;
        }
        String lastPage = null;
        for (JsonNode tab : tabs) {
            if ("page".equals(tab.path("type").asText())) {
                lastPage = textOrNull(tab.path("id"));
            }
        }
        return lastPage == null ? null : tabClose(lastPage);
    }

    /** Body of the most recent HTTP call parsed as JSON, or {@code null} if it was not JSON. */
    public JsonNode lastJson() {
        return lastJson;
    }

    /** Sends one command over a fresh WebSocket and completes with its {@code result}. */
    public CompletableFuture<JsonNode> send(String method, Map<String, ?> params) {
        if (webSocketUrl == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No WebSocket target at " + debuggerAddress));
        }
        int id = ids.incrementAndGet();
        ObjectNode command = JSON.createObjectNode();
        command.put("id", id);
        command.put("method", method);
        command.set("params", JSON.valueToTree(params == null ? Map.of() : params));

        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        WebSocket.Listener listener = new ReplyListener(id, reply);
        http.newWebSocketBuilder()
                .connectTimeout(SEND_TIMEOUT)
                .buildAsync(URI.create(webSocketUrl), listener)
                .thenCompose(ws -> ws.sendText(command.toString(), true).thenApply(sent -> ws))
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        reply.completeExceptionally(error);
                    } else {
                        reply.whenComplete((r, e) -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"));
                    }
                });
        return reply.orTimeout(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private JsonNode request(String verb, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + debuggerAddress + path))
                .method(verb, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();
        try {
            String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            lastJson = parse(body);
        } catch (IOException e) {
            lastJson = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastJson = null;
        }
        return lastJson;
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Collects text frames and completes {@code reply} on the message carrying the expected id. */
    private static final class ReplyListener implements WebSocket.Listener {
        private final int id;
        private final CompletableFuture<JsonNode> reply;
        private final StringBuilder buffer = new StringBuilder();

        ReplyListener(int id, CompletableFuture<JsonNode> reply) {
            this.id = id;
            this.reply = reply;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                JsonNode message = parse(buffer.toString());
                buffer.setLength(0);
                if (message != null && message.path("id").asInt(-1) == id) {
                    if (message.has("error")) {
                        reply.completeExceptionally(new IllegalStateException(message.get("error").toString()));
                    } else {
                        reply.complete(message.path("result"));
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            reply.completeExceptionally(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            reply.completeExceptionally(new IllegalStateException("WebSocket closed before reply: " + reason));
            return null;
        }
    }
}
