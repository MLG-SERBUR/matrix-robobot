package com.robomwm.ai.matrixrobobot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaseoImageFetcherTest {

    @Test
    void logsThumbnailUrlAndResponseBodyOnHttpError() throws Exception {
        byte[] responseBody = "{\"errcode\":\"M_UNKNOWN\",\"error\":\"Unknown media\"}"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(400, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try (PrintStream capturedErr = new PrintStream(logOutput, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);
            String homeserverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PaseoImageFetcher fetcher = new PaseoImageFetcher(
                    HttpClient.newHttpClient(), homeserverUrl, "test-token");

            assertThrows(IOException.class,
                    () -> fetcher.fetchImages(List.of("mxc://media.example/id123"), null));
        } finally {
            System.setErr(originalErr);
            server.stop(0);
        }

        String logs = logOutput.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("/_matrix/client/v1/media/thumbnail/media.example/id123"
                + "?width=640&height=480&method=scale"));
        assertTrue(logs.contains("{\"errcode\":\"M_UNKNOWN\",\"error\":\"Unknown media\"}"));
    }
}
