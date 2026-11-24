package com.robomwm.ai.matrixrobobot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaseoImageFetcher {
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_IMAGES_PER_QUERY = 20;
    private static final long MAX_TOTAL_IMAGE_SIZE = 20 * 1024 * 1024;

    private final HttpClient httpClient;
    private final String homeserverUrl;
    private final String accessToken;

    public PaseoImageFetcher(HttpClient httpClient, String homeserverUrl, String accessToken) {
        this.httpClient = httpClient;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
    }

    public List<PaseoDaemonClient.Image> fetchImages(List<String> imageUrls, AtomicBoolean abortFlag)
            throws java.io.IOException, InterruptedException {
        List<PaseoDaemonClient.Image> images = new ArrayList<>();
        long totalBytes = 0;
        int firstIndex = Math.max(0, imageUrls.size() - MAX_IMAGES_PER_QUERY);
        for (String imageUrl : imageUrls.subList(firstIndex, imageUrls.size())) {
            if (abortFlag != null && abortFlag.get()) throw new CancellationException("Paseo image fetch aborted");
            PaseoDaemonClient.Image image = fetchImage(imageUrl);
            totalBytes += image.base64().length() * 3L / 4L;
            if (totalBytes > MAX_TOTAL_IMAGE_SIZE) {
                throw new java.io.IOException("Images in this history exceed the 20 MB Paseo request limit");
            }
            images.add(image);
        }
        return images;
    }

    private PaseoDaemonClient.Image fetchImage(String matrixUrl) throws java.io.IOException, InterruptedException {
        if (!matrixUrl.startsWith("mxc://")) throw new java.io.IOException("Unsupported Matrix media URL");
        String[] parts = matrixUrl.substring(6).split("/", 2);
        if (parts.length != 2) throw new java.io.IOException("Invalid mxc URL");
        String base = homeserverUrl.endsWith("/")
                ? homeserverUrl.substring(0, homeserverUrl.length() - 1) : homeserverUrl;
        String url = base + "/_matrix/client/v1/media/thumbnail/" + parts[0] + "/" + parts[1]
                + "?width=640&height=480&method=scale";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new java.io.IOException("Matrix media returned HTTP " + response.statusCode());
        }
        byte[] data = response.body();
        if (data.length == 0 || data.length > MAX_IMAGE_SIZE) {
            throw new java.io.IOException("Matrix image is empty or exceeds the 5 MB limit");
        }

        String mimeType = response.headers().firstValue("Content-Type")
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                .filter(value -> value.startsWith("image/"))
                .orElseGet(() -> detectMimeType(data));
        if (mimeType == null) throw new java.io.IOException("Matrix response was not a supported image");
        return new PaseoDaemonClient.Image(Base64.getEncoder().encodeToString(data), mimeType);
    }

    private static String detectMimeType(byte[] data) {
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data.length >= 3 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8 && data[2] == (byte) 0xff) {
            return "image/jpeg";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "image/gif";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
