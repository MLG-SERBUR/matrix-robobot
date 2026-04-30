import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class TestGroq {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null) {
            System.err.println("GROQ_API_KEY env var not set");
            return;
        }

        String payload = "{\"model\": \"groq/compound\", \"messages\": [{\"role\": \"user\", \"content\": \"Hello\"}], \"stream\": true}";
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        client.send(request, HttpResponse.BodyHandlers.ofLines()).body().forEach(System.out::println);
    }
}
