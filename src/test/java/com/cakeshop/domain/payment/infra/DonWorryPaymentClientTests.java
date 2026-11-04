package com.cakeshop.domain.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DonWorryPaymentClientTests {

    private static final String BASE_URL = "http://localhost:8081";
    private static final byte[] KEY = new byte[32];
    private static final String ENCODED_KEY = Base64.getEncoder().encodeToString(KEY);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"), ZoneOffset.UTC);
    private static final String RESPONSE = """
            {
              "paymentId": 101,
              "orderRef": "ORD-100",
              "attemptNo": 1,
              "paymentStatus": "%s",
              "requestedAt": "2026-10-31T03:00:00Z",
              "statusChangedAt": "2026-10-31T03:00:00Z",
              "amount": 25000,
              "declineCode": null
            }
            """;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void request_signsExactBodyWithIdempotencyKey() {
        AtomicReference<MockClientHttpRequest> captured = new AtomicReference<>();
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set((MockClientHttpRequest) request))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON).body(RESPONSE.formatted("PENDING")));

        DonWorryPaymentResponse response = client().request(
                DonWorryPaymentRequest.of("ORD-100", 42L, 25_000L, Instant.parse("2026-10-31T02:59:00Z")),
                "idem-1");

        assertThat(response.paymentId()).isEqualTo(101L);
        assertThat(response.inProgress()).isTrue();
        MockClientHttpRequest request = captured.get();
        HttpHeaders headers = request.getHeaders();
        byte[] body = request.getBodyAsBytes();
        assertThat(new String(body, StandardCharsets.UTF_8))
                .isEqualTo("{\"orderRef\":\"ORD-100\",\"customerRef\":\"42\",\"amount\":25000,"
                        + "\"orderCreatedAt\":\"2026-10-31T02:59:00Z\"}");
        assertThat(headers.getFirst("X-Merchant-Code")).isEqualTo("CAKESHOP");
        assertThat(headers.getFirst("X-Timestamp")).isEqualTo("1793415600");
        assertThat(headers.getFirst("Idempotency-Key")).isEqualTo("idem-1");
        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/json");
        assertThat(headers.getFirst("X-Signature")).isEqualTo(DonWorryPaymentClient.sign(KEY, "CAKESHOP",
                "1793415600", headers.getFirst("X-Nonce"), "POST", "/api/payments", "application/json",
                "idem-1", body));
        server.verify();
    }

    @Test
    void request_truncatesOrderCreatedAtToMicros() {
        DonWorryPaymentRequest request =
                DonWorryPaymentRequest.of("ORD-100", 42L, 25_000L, Instant.parse("2026-10-31T02:59:00.123456789Z"));

        assertThat(request.orderCreatedAt()).isEqualTo(Instant.parse("2026-10-31T02:59:00.123456Z"));
    }

    @Test
    void find_signsWithoutContentTypeAndIdempotencyKey() {
        AtomicReference<MockClientHttpRequest> captured = new AtomicReference<>();
        server.expect(requestTo(BASE_URL + "/api/payments/101"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> captured.set((MockClientHttpRequest) request))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON).body(RESPONSE.formatted("HELD")));

        DonWorryPaymentResponse response = client().find(101L);

        assertThat(response.paymentStatus()).isEqualTo("HELD");
        assertThat(response.inProgress()).isTrue();
        HttpHeaders headers = captured.get().getHeaders();
        assertThat(headers.containsHeader("Idempotency-Key")).isFalse();
        assertThat(headers.containsHeader(HttpHeaders.CONTENT_TYPE)).isFalse();
        assertThat(headers.getFirst("X-Signature")).isEqualTo(DonWorryPaymentClient.sign(KEY, "CAKESHOP",
                "1793415600", headers.getFirst("X-Nonce"), "GET", "/api/payments/101", "", "", new byte[0]));
        server.verify();
    }

    @Test
    void find_finalStatusIsNotInProgress() {
        server.expect(requestTo(BASE_URL + "/api/payments/101"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON).body(RESPONSE.formatted("CAPTURED")));

        assertThat(client().find(101L).inProgress()).isFalse();
    }

    @Test
    void request_errorResponse_keepsProblemCodeOnly() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"code\":\"IDEMPOTENCY_KEY_CONFLICT\",\"detail\":\"secret\"}"));

        assertThatThrownBy(() -> client().request(
                DonWorryPaymentRequest.of("ORD-100", 42L, 25_000L, Instant.parse("2026-10-31T02:59:00Z")),
                "idem-1"))
                .isInstanceOfSatisfying(DonWorryPaymentException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
                    assertThat(exception.resultUnknown()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void request_serverError_isResultUnknown() {
        server.expect(requestTo(BASE_URL + "/api/payments"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client().request(
                DonWorryPaymentRequest.of("ORD-100", 42L, 25_000L, Instant.parse("2026-10-31T02:59:00Z")),
                "idem-1"))
                .isInstanceOfSatisfying(DonWorryPaymentException.class,
                        exception -> assertThat(exception.resultUnknown()).isTrue());
    }

    @Test
    void request_requiresIdempotencyKey() {
        assertThatThrownBy(() -> client().request(
                DonWorryPaymentRequest.of("ORD-100", 42L, 25_000L, Instant.parse("2026-10-31T02:59:00Z")),
                " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsMissingOrShortKey() {
        assertThatThrownBy(() -> client(name -> null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client(name -> Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client(name -> "not-base64!")).isInstanceOf(IllegalStateException.class);
    }

    private DonWorryPaymentClient client() {
        return client(name -> "DONWORRY_MERCHANT_HMAC_CAKESHOP".equals(name) ? ENCODED_KEY : null);
    }

    private DonWorryPaymentClient client(java.util.function.Function<String, String> environment) {
        return new DonWorryPaymentClient(new DonWorryPaymentProperties(), builder.build(), json, CLOCK, environment);
    }
}
