package com.example.matrixbot;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class MatrixHelloBot {
    public static void main(String[] args) throws Exception {
        String homeserver = System.getenv("MATRIX_HOMESERVER_URL");
        String accessToken = System.getenv("MATRIX_ACCESS_TOKEN");
        String roomId = System.getenv("MATRIX_ROOM_ID");
        String message = args.length > 0 ? String.join(" ", args) : "Hello, world!";

        if (homeserver == null || accessToken == null || roomId == null) {
            System.err.println("Missing environment variables: MATRIX_HOMESERVER_URL, MATRIX_ACCESS_TOKEN, MATRIX_ROOM_ID");
            System.exit(2);
        }

        String txnId = "m" + Instant.now().toEpochMilli();
        String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
        String url = homeserver.endsWith("/") ? homeserver.substring(0, homeserver.length()-1) : homeserver;
        String endpoint = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;

        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> payload = Map.of("msgtype", "m.text", "body", message);
        String json = mapper.writeValueAsString(payload);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();

        System.out.println("Sending message to " + roomId + "...");
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Response: " + resp.statusCode());
        System.out.println(resp.body());
    }

    
}
