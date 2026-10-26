package com.cakeshop.domain.payment.infra;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TossPaymentClientStatusTests {

    @ParameterizedTest
    @CsvSource({
            "READY, READY",
            "IN_PROGRESS, IN_PROGRESS",
            "DONE, DONE",
            "CANCELED, CANCELED",
            "ABORTED, ABORTED",
            "EXPIRED, EXPIRED",
            "WAITING_FOR_DEPOSIT, UNKNOWN",
            "PARTIAL_CANCELED, UNKNOWN",
            "NEW_PG_STATUS, UNKNOWN"
    })
    void find_tossStatus_mapsToCommonStatus(String tossStatus, PaymentGatewayStatus expected) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://payments.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentClient client = new TossPaymentClient(builder.build(), "test-secret-key");
        server.expect(requestTo("https://payments.test/v1/payments/payment-key"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "ORD-100",
                          "method": "카드",
                          "status": "%s",
                          "totalAmount": 30000,
                          "approvedAt": "2026-08-01T10:00:00+09:00",
                          "cancels": [{
                            "transactionKey": "cancel-transaction",
                            "canceledAt": "2026-08-01T10:01:00+09:00"
                          }]
                        }
                        """.formatted(tossStatus), MediaType.APPLICATION_JSON));

        PaymentLookupResult result = client.find("payment-key").orElseThrow();

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.cancellation().status()).isEqualTo(expected);
        assertThat(result.toApprovalResult()).isEqualTo(new ApprovalResult(
                result.paymentKey(), result.orderId(), result.method(), expected,
                result.totalAmount(), result.approvedAt()
        ));
        server.verify();
    }
}
