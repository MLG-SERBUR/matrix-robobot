package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;

/**
 * Handles fetching and encoding images from Matrix media URLs.
 */
public class ImageFetcher {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;

    // Maximum image size to fetch (5MB)
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;

    public ImageFetcher(HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
    }

    /**
     * Fetch and base64-encode images from Matrix media URLs.
     * Returns list of base64 data URLs (data:image/jpeg;base64,...).
     * Skips images that fail to download or exceed size limits.
     */
    public List<String> fetchAndEncodeImages(List<String> imageUrls) {
        List<String> encodedImages = new ArrayList<>();

        for (String matrixUrl : imageUrls) {
            try {
                String base64Data = fetchImageAsBase64(matrixUrl);
                if (base64Data != null) {
                    // Determine MIME type (assume JPEG for now, could be enhanced)
                    String mimeType = "image/jpeg";
                    if (matrixUrl.toLowerCase().contains(".png")) {
                        mimeType = "image/png";
                    } else if (matrixUrl.toLowerCase().contains(".gif")) {
                        mimeType = "image/gif";
                    }
                    encodedImages.add("data:" + mimeType + ";base64," + base64Data);
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch image " + matrixUrl + ": " + e.getMessage());
                // Continue with other images
            }
        }

        return encodedImages;
    }

    private String fetchImageAsBase64(String matrixUrl) throws Exception {
        // Convert Matrix media URL to full HTTP URL
        String fullUrl;
        if (matrixUrl.startsWith("mxc://")) {
            // mxc://server/media_id format
            String[] parts = matrixUrl.substring(6).split("/");
            if (parts.length == 2) {
                String server = parts[0];
                String mediaId = parts[1];
                fullUrl = homeserverUrl + "/_matrix/media/r0/download/" + server + "/" + mediaId;
            } else {
                throw new Exception("Invalid mxc URL format: " + matrixUrl);
            }
        } else {
            // Assume it's already a full URL
            fullUrl = matrixUrl;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new Exception("HTTP " + response.statusCode() + " for " + fullUrl);
        }

        byte[] imageData = response.body();
        if (imageData.length > MAX_IMAGE_SIZE) {
            System.out.println("Skipping image " + matrixUrl + " - size " + imageData.length + " exceeds limit " + MAX_IMAGE_SIZE);
            return null;
        }

        return Base64.getEncoder().encodeToString(imageData);
    }
}