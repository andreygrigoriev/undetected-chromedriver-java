package io.andreygrigoriev.uc.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local HTTP server that serves canned responses by path and records the requests it receives. */
public final class StubHttpServer implements AutoCloseable {

    public record Request(String method, String path) {}

    private record Response(int status, byte[] body) {}

    private final HttpServer server;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path));
            Response response = responses.getOrDefault(path, new Response(404, new byte[0]));
            exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
            if (response.body().length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response.body());
                }
            }
            exchange.close();
        });
        server.start();
    }

    public StubHttpServer respond(String path, int status, String body) {
        return respond(path, status, body.getBytes(StandardCharsets.UTF_8));
    }

    public StubHttpServer respond(String path, int status, byte[] body) {
        responses.put(path, new Response(status, body));
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
