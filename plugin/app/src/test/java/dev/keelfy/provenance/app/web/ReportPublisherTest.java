package dev.keelfy.provenance.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import lombok.val;
import org.junit.jupiter.api.Test;

class ReportPublisherTest {

    private static final String TOKEN = "abcdefghijklmnopqrstuv";

    @Test
    void signatureMatchesRfc4231Vector() {
        assertThat(ReportPublisher.sign("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void publishSignsTheBodyAndBuildsTheLink() throws Exception {
        val seen = new AtomicReference<String[]>();
        val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/reports", exchange -> {
            val body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            val headers = exchange.getRequestHeaders();
            seen.set(new String[] {
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                body,
                headers.getFirst("X-Provenance-Timestamp"),
                headers.getFirst("X-Provenance-Signature")
            });
            val reply = ("{\"token\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            val endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            val link =
                    new ReportPublisher(endpoint, "s3cret").publish("{\"v\":1}").get();

            assertThat(link).isEqualTo(endpoint + "/view#" + TOKEN);
            val request = seen.get();
            assertThat(request[0]).isEqualTo("POST");
            assertThat(request[2]).isEqualTo("{\"v\":1}");
            assertThat(request[4]).isEqualTo(ReportPublisher.sign("s3cret", request[3] + "\n/api/reports\n{\"v\":1}"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failuresReachTheCaller() throws Exception {
        val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            val publisher = new ReportPublisher(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "x");

            assertThatThrownBy(() -> publisher.publish("{}").get()).hasMessageContaining("HTTP 401");
            assertThatThrownBy(() -> publisher.revoke(TOKEN).get()).hasMessageContaining("HTTP 401");
            assertThatThrownBy(() -> publisher.revoke("../etc").get()).hasMessageContaining("not a report token");
        } finally {
            server.stop(0);
        }
    }
}
