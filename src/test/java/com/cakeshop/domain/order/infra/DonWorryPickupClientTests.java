package com.cakeshop.domain.order.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cakeshop.domain.order.dto.DonWorryPickupMessage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class DonWorryPickupClientTests {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);

    // P1 문서의 독립 Python 벡터와 같아야 별도 프로젝트 사이의 정규화 차이를 잡을 수 있다.
    @ParameterizedTest
    @CsvSource({
            "schedule,5b785c814ec2b9f678ab7d2b8e012821159aea609a70d7010eb5fe6f5ac3a429",
            "completion,96e4bb0f38073f9c87591f05d7f2f4bfcdf088e328f71a18573459573b8e52a8"
    })
    void signMatchesP1FixedVectors(String action, String expected) {
        String field = action.equals("schedule") ? "scheduledPickupAt" : "pickedUpAt";
        byte[] body = ("{\"" + field + "\":\"2026-10-27T06:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(DonWorryPickupClient.sign(KEY, "CAKESHOP", "1793080800",
                "11111111-1111-4111-8111-111111111111", "PUT",
                "/api/v1/order-fulfillments/ORDER-1/" + action, "application/json", "", body)).isEqualTo(expected);
    }

    @Test
    void sendsExactSignedBodyAndFreshNonceOnEveryDelivery() throws Exception {
        var received = new ArrayList<Received>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            var headers = exchange.getRequestHeaders();
            synchronized (received) {
                received.add(new Received(exchange.getRequestURI().getRawPath(), exchange.getRequestMethod(),
                        headers.getFirst("Content-Type"), headers.getFirst("X-Merchant-Code"),
                        headers.getFirst("X-Timestamp"), headers.getFirst("X-Nonce"),
                        headers.getFirst("X-Signature"), body));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var json = JsonMapper.builder().findAndAddModules().build();
            var props = properties("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new DonWorryPickupClient(props, json, CLOCK, ignored -> Base64.getEncoder().encodeToString(KEY));
            var message = new DonWorryPickupMessage("ORD-TEST", Instant.parse("2026-10-27T06:00:00Z"),
                    Instant.parse("2026-10-27T06:15:00Z"),
                    List.of(new DonWorryPickupMessage.Item("딸기 케이크", 2),
                            new DonWorryPickupMessage.Item("초코 케이크", 1)));
            client.send(message, false);
            client.send(message, false);
            client.send(message, true);
            assertThat(received).hasSize(3);
            assertThat(received).extracting(Received::nonce).doesNotHaveDuplicates();
            for (var request : received) {
                assertThat(request.method()).isEqualTo("PUT");
                assertThat(request.merchant()).isEqualTo("CAKESHOP");
                assertThat(request.timestamp()).isEqualTo(Long.toString(CLOCK.instant().getEpochSecond()));
                assertThat(UUID.fromString(request.nonce()).version()).isEqualTo(4);
                assertThat(request.signature()).isEqualTo(DonWorryPickupClient.sign(KEY, request.merchant(),
                        request.timestamp(), request.nonce(), request.method(), request.path(),
                        request.contentType(), "", request.body()));
            }
            var schedule = json.readTree(received.getFirst().body());
            assertThat(schedule.path("scheduledPickupAt").asString()).isEqualTo("2026-10-27T06:00:00Z");
            assertThat(schedule.path("items").size()).isEqualTo(2);
            assertThat(schedule.path("items").get(0).path("productName").asString()).isEqualTo("딸기 케이크");
            assertThat(schedule.path("items").get(0).path("quantity").intValue()).isEqualTo(2);
            assertThat(schedule.has("merchantId")).isFalse();
            assertThat(received.get(1).body()).isEqualTo(received.getFirst().body());
            assertThat(received.getLast().path()).isEqualTo("/api/v1/order-fulfillments/ORD-TEST/completion");
            var completion = json.readTree(received.getLast().body());
            assertThat(completion.path("pickedUpAt").asString()).isEqualTo("2026-10-27T06:15:00Z");
            assertThat(completion.size()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotTreatErrorResponseAsSuccessOrFollowRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "https://example.invalid/private");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new DonWorryPickupClient(properties("http://127.0.0.1:" + server.getAddress().getPort()),
                    JsonMapper.builder().findAndAddModules().build(), CLOCK,
                    ignored -> Base64.getEncoder().encodeToString(KEY));
            assertThatThrownBy(() -> client.send(new DonWorryPickupMessage("ORD-1", null, CLOCK.instant(), List.of()), true))
                    .isInstanceOf(IllegalStateException.class).hasMessage("DonWorry 픽업 응답 상태: 302");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingKeyAndInsecureRemoteBaseUrl() {
        var json = JsonMapper.builder().build();
        assertThatThrownBy(() -> new DonWorryPickupClient(properties("https://example.invalid"), json, CLOCK, ignored -> null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DonWorryPickupClient(properties("http://example.invalid"), json, CLOCK,
                ignored -> Base64.getEncoder().encodeToString(KEY))).isInstanceOf(IllegalStateException.class);
    }

    private DonWorryPickupProperties properties(String url) {
        var properties = new DonWorryPickupProperties();
        properties.setBaseUrl(URI.create(url));
        return properties;
    }

    private record Received(String path, String method, String contentType, String merchant,
                            String timestamp, String nonce, String signature, byte[] body) {
    }
}
