package com.cakeshop.domain.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DonWorryPaymentGatewayTests {

    private final DonWorryPaymentClient client = mock(DonWorryPaymentClient.class);
    private final DonWorryPaymentGateway gateway = new DonWorryPaymentGateway(
            client, Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"), ZoneId.of("Asia/Seoul")));

    @Test
    void provider_isOwn() {
        assertThat(gateway.provider()).isEqualTo(PaymentProvider.OWN);
        assertThat(gateway.isAvailable()).isTrue();
    }

    @Test
    void paymentKey_roundTripsOnlyValidFormat() {
        assertThat(DonWorryPaymentGateway.paymentKey(101L)).isEqualTo("DW-101");
        assertThat(DonWorryPaymentGateway.paymentId("DW-101")).contains(101L);
        assertThat(DonWorryPaymentGateway.paymentId("DW-0")).isEmpty();
        assertThat(DonWorryPaymentGateway.paymentId("toss-key")).isEmpty();
        assertThat(DonWorryPaymentGateway.paymentId("DW-99999999999999999999")).isEmpty();
        assertThat(DonWorryPaymentGateway.paymentId(null)).isEmpty();
    }

    @Test
    void toGatewayStatus_mapsDonWorryStatuses() {
        assertThat(DonWorryPaymentGateway.toGatewayStatus("CAPTURED")).isEqualTo(PaymentGatewayStatus.DONE);
        assertThat(DonWorryPaymentGateway.toGatewayStatus("PENDING")).isEqualTo(PaymentGatewayStatus.IN_PROGRESS);
        assertThat(DonWorryPaymentGateway.toGatewayStatus("HELD")).isEqualTo(PaymentGatewayStatus.IN_PROGRESS);
        assertThat(DonWorryPaymentGateway.toGatewayStatus("DENIED")).isEqualTo(PaymentGatewayStatus.ABORTED);
        assertThat(DonWorryPaymentGateway.toGatewayStatus("DECLINED")).isEqualTo(PaymentGatewayStatus.ABORTED);
        assertThat(DonWorryPaymentGateway.toGatewayStatus("VOIDED")).isEqualTo(PaymentGatewayStatus.ABORTED);
        assertThat(DonWorryPaymentGateway.toGatewayStatus("EXPIRED")).isEqualTo(PaymentGatewayStatus.EXPIRED);
        assertThat(DonWorryPaymentGateway.toGatewayStatus("NEW_STATUS")).isEqualTo(PaymentGatewayStatus.UNKNOWN);
    }

    @Test
    void find_mapsCapturedPaymentInShopTimeZone() {
        when(client.find(101L)).thenReturn(response("CAPTURED"));

        PaymentLookupResult result = gateway.find("DW-101").orElseThrow();

        assertThat(result.paymentKey()).isEqualTo("DW-101");
        assertThat(result.orderId()).isEqualTo("ORD-100");
        assertThat(result.status()).isEqualTo(PaymentGatewayStatus.DONE);
        assertThat(result.totalAmount()).isEqualTo(25_000L);
        assertThat(result.approvedAt()).isEqualTo(LocalDateTime.parse("2026-10-31T12:01:00"));
        assertThat(result.cancellation()).isNull();
    }

    @Test
    void find_notFoundOrInvalidKey_isEmpty() {
        when(client.find(404L)).thenThrow(new DonWorryPaymentException(404, "PAYMENT_NOT_FOUND"));

        assertThat(gateway.find("DW-404")).isEmpty();
        assertThat(gateway.find("not-donworry")).isEmpty();
    }

    @Test
    void find_otherFailure_isLookupFailure() {
        when(client.find(101L)).thenThrow(new DonWorryPaymentException(0, null));

        assertThatThrownBy(() -> gateway.find("DW-101"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED));
    }

    @Test
    void approve_returnsOnlyCapturedPayment() {
        when(client.find(101L)).thenReturn(response("CAPTURED"));

        ApprovalResult approval = gateway.approve("DW-101", "ORD-100", 25_000L, "unused");

        assertThat(approval.status()).isEqualTo(PaymentGatewayStatus.DONE);
        assertThat(approval.paymentKey()).isEqualTo("DW-101");
        assertThat(approval.method()).isEqualTo("카드");
    }

    @Test
    void approve_heldPayment_isNotApproval() {
        when(client.find(101L)).thenReturn(response("HELD"));

        assertThatThrownBy(() -> gateway.approve("DW-101", "ORD-100", 25_000L, "unused"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancel_isNotSupportedYet() {
        assertThatThrownBy(() -> gateway.cancel("DW-101", "고객 취소", "key"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        verifyNoInteractions(client);
    }

    private DonWorryPaymentResponse response(String status) {
        return new DonWorryPaymentResponse(101L, "ORD-100", 1, status,
                Instant.parse("2026-10-31T03:00:00Z"), Instant.parse("2026-10-31T03:01:00Z"), 25_000L, null);
    }
}
