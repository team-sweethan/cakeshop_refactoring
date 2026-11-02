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
    void sessionRequest_truncatesOrderCreatedAtToMicros() {
        DonWorryPaymentSessionRequest request = sessionRequest(Instant.parse("2026-10-31T02:59:00.123456789Z"));

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

    // 돈워리 MerchantWebhookSignerServiceTests와 같은 고정 벡터(키 0x00 × 32)
    private static final String WEBHOOK_SIGNATURE = "1eece5c756406c6efbde75b08f7fd69471a87cff3630f0679dc8ddb23eade07a";
    private static final byte[] WEBHOOK_BODY = "{\"paymentId\":4}".getBytes(StandardCharsets.UTF_8);

    @Test
    void verifyWebhook_acceptsDonWorrySignature() {
        assertThat(client().verifyWebhook("CAKESHOP", "1793415600", "evt_4_CAPTURED_A", WEBHOOK_SIGNATURE,
                WEBHOOK_BODY)).isTrue();
    }

    @Test
    void verifyWebhook_rejectsTamperedOrStaleOrOtherMerchant() {
        DonWorryPaymentClient client = client();
        assertThat(client.verifyWebhook("CAKESHOP", "1793415600", "evt_4_CAPTURED_A", WEBHOOK_SIGNATURE,
                "{\"paymentId\":5}".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(client.verifyWebhook("CAKESHOP", "1793415600", "evt_4_VOIDED_A", WEBHOOK_SIGNATURE,
                WEBHOOK_BODY)).isFalse();
        // 서명 시각이 5분보다 오래됐다
        assertThat(client.verifyWebhook("CAKESHOP", "1793415299", "evt_4_CAPTURED_A", WEBHOOK_SIGNATURE,
                WEBHOOK_BODY)).isFalse();
        assertThat(client.verifyWebhook("OTHER", "1793415600", "evt_4_CAPTURED_A", WEBHOOK_SIGNATURE,
                WEBHOOK_BODY)).isFalse();
        assertThat(client.verifyWebhook("CAKESHOP", "1793415600", "evt_4_CAPTURED_A", null, WEBHOOK_BODY))
                .isFalse();
    }

    @Test
    void createSession_signsBodyWithIdempotencyKeyAndReadsCheckoutUrl() {
        AtomicReference<MockClientHttpRequest> captured = new AtomicReference<>();
        server.expect(requestTo(BASE_URL + "/api/payment-sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set((MockClientHttpRequest) request))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sessionKey\":\"pss_1\",\"checkoutUrl\":\"http://localhost:5174/checkout/pss_1\","
                                + "\"expiresAt\":\"2026-10-31T03:09:00Z\"}"));

        DonWorryPaymentSessionResponse session = client().createSession(new DonWorryPaymentSessionRequest(
                "ORD-100", "42", 25_000L, Instant.parse("2026-10-31T02:59:00Z"), "딸기 케이크", null,
                "http://localhost:8080/orders/1/payment/donworry/return",
                "http://localhost:8080/orders/1/payment/fail", null), "idem-1");

        assertThat(session.checkoutUrl()).isEqualTo("http://localhost:5174/checkout/pss_1");
        HttpHeaders headers = captured.get().getHeaders();
        byte[] body = captured.get().getBodyAsBytes();
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("buyerEmail").doesNotContain("notifyUrl");
        assertThat(headers.getFirst("Idempotency-Key")).isEqualTo("idem-1");
        assertThat(headers.getFirst("X-Signature")).isEqualTo(DonWorryPaymentClient.sign(KEY, "CAKESHOP",
                "1793415600", headers.getFirst("X-Nonce"), "POST", "/api/payment-sessions", "application/json",
                "idem-1", body));
        server.verify();
    }

    // DonWorry 취소 계약(#381): 본문은 reason 하나뿐, Idempotency-Key는 UUID, 응답의 requestStatus로 완료를 판단한다
    @Test
    void cancel_signsReasonOnlyBodyWithIdempotencyKey() {
        String key = "6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b";
        AtomicReference<MockClientHttpRequest> captured = new AtomicReference<>();
        server.expect(requestTo(BASE_URL + "/api/payments/101/cancellations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set((MockClientHttpRequest) request))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"requestKey\":\"" + key + "\",\"paymentId\":101,\"requestStatus\":\"SUCCEEDED\","
                                + "\"cancellationId\":501,\"operation\":\"VOID\",\"amount\":25000,"
                                + "\"requestedAt\":\"2026-10-31T03:00:00Z\",\"updatedAt\":\"2026-10-31T03:00:01Z\","
                                + "\"completedAt\":\"2026-10-31T03:00:01Z\",\"code\":null,\"nextAction\":\"NONE\"}"));

        DonWorryCancellationResponse response = client().cancel(101L, "고객 요청으로 주문 취소", key);

        assertThat(response.completed()).isTrue();
        assertThat(response.cancellationId()).isEqualTo(501L);
        HttpHeaders headers = captured.get().getHeaders();
        byte[] body = captured.get().getBodyAsBytes();
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"reason\":\"고객 요청으로 주문 취소\"}");
        assertThat(headers.getFirst("Idempotency-Key")).isEqualTo(key);
        assertThat(headers.getFirst("X-Signature")).isEqualTo(DonWorryPaymentClient.sign(KEY, "CAKESHOP",
                "1793415600", headers.getFirst("X-Nonce"), "POST", "/api/payments/101/cancellations",
                "application/json", key, body));
        server.verify();
    }

    @Test
    void findLatestAttempt_signsPathWithQuery() {
        AtomicReference<MockClientHttpRequest> captured = new AtomicReference<>();
        server.expect(requestTo(BASE_URL + "/api/payment-orders?orderRef=ORD-100&size=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> captured.set((MockClientHttpRequest) request))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"orderRef\":\"ORD-100\",\"latestPayment\":" + RESPONSE.formatted("HELD")
                                + ",\"attempts\":[],\"nextBeforeAttemptNo\":null}"));

        DonWorryPaymentResponse latest = client().findLatestAttempt("ORD-100").orElseThrow();

        assertThat(latest.paymentStatus()).isEqualTo("HELD");
        assertThat(latest.endedWithoutCharge()).isFalse();
        HttpHeaders headers = captured.get().getHeaders();
        assertThat(headers.getFirst("X-Signature")).isEqualTo(DonWorryPaymentClient.sign(KEY, "CAKESHOP",
                "1793415600", headers.getFirst("X-Nonce"), "GET", "/api/payment-orders?orderRef=ORD-100&size=1",
                "", "", new byte[0]));
    }

    @Test
    void findLatestAttempt_orderNotFound_isEmpty() {
        server.expect(requestTo(BASE_URL + "/api/payment-orders?orderRef=ORD-404&size=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"ORDER_NOT_FOUND\"}"));

        assertThat(client().findLatestAttempt("ORD-404")).isEmpty();
    }

    @Test
    void find_finalStatusIsNotInProgress() {
        server.expect(requestTo(BASE_URL + "/api/payments/101"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON).body(RESPONSE.formatted("CAPTURED")));

        assertThat(client().find(101L).inProgress()).isFalse();
    }

    @Test
    void createSession_errorResponse_keepsProblemCodeOnly() {
        server.expect(requestTo(BASE_URL + "/api/payment-sessions"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"code\":\"IDEMPOTENCY_KEY_CONFLICT\",\"detail\":\"secret\"}"));

        assertThatThrownBy(() -> client().createSession(sessionRequest(Instant.parse("2026-10-31T02:59:00Z")), "idem-1"))
                .isInstanceOfSatisfying(DonWorryPaymentException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(409);
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
                    assertThat(exception.resultUnknown()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void createSession_serverError_isResultUnknown() {
        server.expect(requestTo(BASE_URL + "/api/payment-sessions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client().createSession(sessionRequest(Instant.parse("2026-10-31T02:59:00Z")), "idem-1"))
                .isInstanceOfSatisfying(DonWorryPaymentException.class,
                        exception -> assertThat(exception.resultUnknown()).isTrue());
    }

    @Test
    void createSession_requiresIdempotencyKey() {
        assertThatThrownBy(() -> client().createSession(sessionRequest(Instant.parse("2026-10-31T02:59:00Z")), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DonWorryPaymentSessionRequest sessionRequest(Instant orderCreatedAt) {
        return new DonWorryPaymentSessionRequest("ORD-100", "42", 25_000L, orderCreatedAt, "딸기 케이크", null,
                "http://localhost:8080/orders/1/payment/donworry/return",
                "http://localhost:8080/orders/1/payment/fail", null);
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
