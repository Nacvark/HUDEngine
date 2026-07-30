package io.github.nacvark.hudengine.paper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The pack host is the one piece of this plugin that faces the open internet, so it is worth knowing
 * that it serves what it should and nothing else.
 */
class PackHostTest {

    private static final String PATH = "/hudengine.zip";

    private PackHost host;
    private int port;

    @AfterEach
    void stopHost() {
        if (host != null) {
            host.stop();
        }
    }

    private void startWith(Path packFile) throws IOException {
        port = freePort();
        host = PackHost.start("127.0.0.1", port, PATH);
        assertNotNull(host, "the host did not start on a free port");
        if (packFile != null) {
            host.publish(packFile);
        }
    }

    @Test
    void servesThePublishedPack(@TempDir Path dir) throws Exception {
        byte[] content = "PK pretend this is a zip".getBytes();
        Path pack = Files.write(dir.resolve("pack.zip"), content);
        startWith(pack);

        HttpResponse<byte[]> response = get(PATH);

        assertEquals(200, response.statusCode());
        assertArrayEquals(content, response.body());
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void servesTheNewFileAfterARepublish(@TempDir Path dir) throws Exception {
        Path pack = Files.write(dir.resolve("pack.zip"), "first".getBytes());
        startWith(pack);

        Files.write(pack, "second".getBytes());
        host.publish(pack);

        // A reload rebuilds the pack; serving the old bytes would hand clients a file whose hash no
        // longer matches what the server told them to expect.
        assertArrayEquals("second".getBytes(), get(PATH).body());
    }

    @Test
    void refusesAnythingButTheOnePath(@TempDir Path dir) throws Exception {
        startWith(Files.write(dir.resolve("pack.zip"), "content".getBytes()));

        // The JDK's context matching is by prefix, so paths below it reach the handler and have to
        // be turned away explicitly rather than by accident.
        assertEquals(404, get(PATH + "/../server.properties").statusCode());
        assertEquals(404, get(PATH + "/anything").statusCode());
    }

    @Test
    void refusesWritesAndOtherMethods(@TempDir Path dir) throws Exception {
        startWith(Files.write(dir.resolve("pack.zip"), "content".getBytes()));

        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri(PATH))
                .method("POST", HttpRequest.BodyPublishers.ofString("x")));

        assertEquals(405, response.statusCode());
    }

    @Test
    void answersHeadWithoutABody(@TempDir Path dir) throws Exception {
        startWith(Files.write(dir.resolve("pack.zip"), "content".getBytes()));

        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri(PATH)).method("HEAD",
                HttpRequest.BodyPublishers.noBody()));

        assertEquals(200, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void reportsNotFoundBeforeAnythingIsPublished() throws Exception {
        startWith(null);

        assertEquals(404, get(PATH).statusCode());
    }

    @Test
    void reportsABusyPortByThrowing() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            // The caller turns this into a localised warning and disables delivery, rather than
            // letting a busy port abort plugin startup.
            assertThrows(IOException.class,
                    () -> PackHost.start("127.0.0.1", taken.getLocalPort(), PATH));
        }
    }

    /* ---------------- helpers ---------------- */

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private static HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            return client.send(request.timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
