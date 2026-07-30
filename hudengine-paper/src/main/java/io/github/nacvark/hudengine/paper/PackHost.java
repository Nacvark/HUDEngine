package io.github.nacvark.hudengine.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * A minimal HTTP server that hands the built pack to clients.
 *
 * Exists so a server owner can turn the HUD on without also standing up a web server. Serving one
 * static file over HTTP is not worth a dependency, so this uses the JDK's own server.
 *
 * Deliberately narrow: one path, GET and HEAD only, no directory listing, no range requests, no
 * other file reachable. It is on the public internet by definition, so the smaller its surface the
 * better. Anything more demanding — a CDN, TLS, bandwidth control — is what the external URL mode is
 * for.
 */
final class PackHost {

    private static final int STOP_GRACE_SECONDS = 1;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int OK = 200;

    private final HttpServer server;
    private final String path;

    private volatile byte[] pack = new byte[0];

    private PackHost(HttpServer server, String path) {
        this.server = server;
        this.path = path;
    }

    /**
     * Binds the port and starts serving.
     *
     * Reports failure by throwing rather than by logging, so that nothing here has to know which
     * language the server runs in and the class stays testable without a running plugin.
     *
     * @param path the single URL path the pack is served from, starting with a slash
     */
    static PackHost start(String bind, int port, String path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        PackHost host = new PackHost(server, path);
        server.createContext(path, host::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return host;
    }

    /** Loads the pack into memory. Called after every compile so a reload serves the new file. */
    void publish(Path file) throws IOException {
        pack = Files.readAllBytes(file);
    }

    void stop() {
        server.stop(STOP_GRACE_SECONDS);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.sendResponseHeaders(METHOD_NOT_ALLOWED, -1);
                return;
            }
            // The context matches by prefix, so anything below the path must be refused explicitly.
            if (!exchange.getRequestURI().getPath().equals(path)) {
                exchange.sendResponseHeaders(NOT_FOUND, -1);
                return;
            }
            byte[] body = pack;
            if (body.length == 0) {
                exchange.sendResponseHeaders(NOT_FOUND, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(OK, -1);
                return;
            }
            exchange.sendResponseHeaders(OK, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
