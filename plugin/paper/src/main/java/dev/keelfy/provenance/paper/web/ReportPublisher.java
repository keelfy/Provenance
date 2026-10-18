package dev.keelfy.provenance.paper.web;

import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.keelfy.provenance.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.val;

/**
 * Talks to the web server: publishes a snapshot and revokes links. Every request carries {@code X-Provenance-Timestamp}
 * (millis) and {@code X-Provenance-Signature}, the hex HMAC-SHA256 of {@code timestamp \n path \n body} under the
 * shared secret, so the server can reject forged and stale requests.
 *
 * @author keelfy
 */
@Singleton
public final class ReportPublisher {

    private static final String REPORTS = "/api/reports";
    private static final String HMAC = "HmacSHA256";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String endpoint;
    private final String secret;
    // HTTP/1.1 only: on plain http the default client sends an h2c upgrade, which Bun's server answers by hanging up.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    @Inject
    public ReportPublisher(Settings settings) {
        this(settings.webEndpoint(), settings.webSecret());
    }

    ReportPublisher(String endpoint, String secret) {
        this.endpoint = endpoint;
        this.secret = secret;
    }

    /** @return the link to open the report, {@code <endpoint>/view#<token>} */
    public CompletableFuture<String> publish(String reportJson) {
        return send("POST", REPORTS, reportJson).thenApply(body -> {
            val token =
                    JsonParser.parseString(body).getAsJsonObject().get("token").getAsString();
            return endpoint + "/view#" + token;
        });
    }

    /** Fails if the token is not a report token, or the server does not know it. */
    public CompletableFuture<Void> revoke(String token) {
        if (!TOKEN.matcher(token).matches()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("not a report token"));
        }
        return send("DELETE", REPORTS + "/" + token, "").thenApply(body -> null);
    }

    private CompletableFuture<String> send(String method, String path, String body) {
        val timestamp = Long.toString(System.currentTimeMillis());
        val request = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Provenance-Timestamp", timestamp)
                .header("X-Provenance-Signature", sign(secret, timestamp + "\n" + path + "\n" + body))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("web server answered HTTP " + response.statusCode());
                    }
                    return response.body();
                });
    }

    static String sign(String secret, String message) {
        try {
            val mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
