package com.cakeshop.domain.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentClientTests {

    private static final String BASE_URL = "https://api.tosspayments.test";
    private static final String SECRET_KEY = "test-secret-key";

    @Test
    void find_donePayment_mapsApprovalAndCancellationData() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentClient client = new TossPaymentClient(builder.build(), SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andExpect(header("Authorization", authorization()))
                .andRespond(withSuccess(
                        """
                        {
                          "paymentKey": "payment-key",
                          "orderId": "ORD-100",
                          "method": "카드",
                          "status": "CANCELED",
                          "totalAmount": 30000,
                          "approvedAt": "2026-08-01T10:00:00+09:00",
                          "cancels": [{
                            "transactionKey": "cancel-transaction",
                            "canceledAt": "2026-08-01T10:01:00+09:00"
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        PaymentLookupResult result = client.find("payment-key").orElseThrow();

        assertThat(result.status()).isEqualTo(PaymentGatewayStatus.CANCELED);
        assertThat(result.orderId()).isEqualTo("ORD-100");
        assertThat(result.totalAmount()).isEqualTo(30_000L);
        assertThat(result.cancellation().transactionKey())
                .isEqualTo("cancel-transaction");
        server.verify();
    }

    @Test
    void approve_idempotencyKey_sendsHeaderAndMapsDonePayment() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentClient client = new TossPaymentClient(builder.build(), SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "PAY-1"))
                .andRespond(withSuccess(
                        """
                        {
                          "paymentKey": "payment-key",
                          "orderId": "ORD-100",
                          "method": "카드",
                          "status": "DONE",
                          "totalAmount": 30000,
                          "approvedAt": "2026-08-01T10:00:00+09:00",
                          "cancels": []
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        ApprovalResult result = client.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        );

        assertThat(result.status()).isEqualTo(PaymentGatewayStatus.DONE);
        assertThat(result.totalAmount()).isEqualTo(30_000L);
        server.verify();
    }

    @Test
    void find_notFound_returnsEmpty() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentClient client = new TossPaymentClient(builder.build(), SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andRespond(withResourceNotFound());

        Optional<PaymentLookupResult> result = client.find("payment-key");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void constructor_nonPositiveTimeout_rejectsConfiguration() {
        assertThatThrownBy(() -> new TossPaymentClient(
                BASE_URL,
                SECRET_KEY,
                Duration.ZERO,
                Duration.ofSeconds(5)
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void find_serverError_returnsSafeLookupFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentClient client = new TossPaymentClient(builder.build(), SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andRespond(org.springframework.test.web.client.response
                        .MockRestResponseCreators.withServerError());

        assertThatThrownBy(() -> client.find("payment-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED)
                );
        server.verify();
    }

    private String authorization() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8)
        );
    }
}
