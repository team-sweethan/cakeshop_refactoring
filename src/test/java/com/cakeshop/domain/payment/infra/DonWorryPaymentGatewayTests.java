package com.cakeshop.domain.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    // 돈워리 결제창이 failUrl에 붙이는 실제 code 값과 맞아야 실패 안내가 갈린다 (DonWorry PaymentSessionService)
    @Test
    void failureCodes_matchDonWorryCheckoutFailUrl() {
        assertThat(gateway.isUserCanceled("USER_CANCELED")).isTrue();
        assertThat(gateway.isCardRejected("AUTHORIZATION_DECLINED")).isTrue();
        assertThat(gateway.isUserCanceled("DENIED")).isFalse();
        assertThat(gateway.isCardRejected("DENIED")).isFalse();
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
    void approve_capturedPayment_isApproval() {
        when(client.find(101L)).thenReturn(response("CAPTURED"));

        ApprovalResult approval = gateway.approve("DW-101", "ORD-100", 25_000L, "unused");

        assertThat(approval.status()).isEqualTo(PaymentGatewayStatus.DONE);
        assertThat(approval.paymentKey()).isEqualTo("DW-101");
        assertThat(approval.method()).isEqualTo("카드");
    }

    @Test
    void approve_authorizedHeldOrPendingPayment_isApproval() {
        // 카드 승인 뒤 보류·매입 대기는 결제 완료다. 직원 거절(VOIDED)은 이후 웹훅으로 처리한다.
        when(client.find(101L)).thenReturn(response("HELD", true), response("PENDING", true));

        assertThat(gateway.approve("DW-101", "ORD-100", 25_000L, "unused").status())
                .isEqualTo(PaymentGatewayStatus.DONE);
        assertThat(gateway.approve("DW-101", "ORD-100", 25_000L, "unused").status())
                .isEqualTo(PaymentGatewayStatus.DONE);
    }

    @Test
    void approve_notAuthorizedPendingOrVoided_isNotApproval() {
        when(client.find(101L)).thenReturn(response("PENDING", false), response("VOIDED", true));

        assertThatThrownBy(() -> gateway.approve("DW-101", "ORD-100", 25_000L, "unused"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.approve("DW-101", "ORD-100", 25_000L, "unused"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancel_succeeded_returnsCanceledResultWithStableUuidKey() {
        when(client.cancel(101L, "고객 취소", "6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b"))
                .thenReturn(cancellation("SUCCEEDED", 501L, Instant.parse("2026-10-31T03:05:00Z")));

        CancellationResult result = gateway.cancel(
                "DW-101", "  고객 취소  ", "CANCEL-6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b");

        assertThat(result.status()).isEqualTo(PaymentGatewayStatus.CANCELED);
        assertThat(result.transactionKey()).isEqualTo("DW-CANCEL-501");
        assertThat(result.canceledAt()).isEqualTo(java.time.LocalDateTime.parse("2026-10-31T12:05:00"));
    }

    @Test
    void cancel_alreadyCanceledByStaffVoid_isCompletedWithoutCancellationId() {
        when(client.cancel(eq(101L), anyString(), anyString())).thenReturn(cancellation("ALREADY_CANCELED", null, null));

        CancellationResult result = gateway.cancel("DW-101", "고객 취소", "CANCEL-6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b");

        assertThat(result.transactionKey()).isEqualTo("DW-CANCEL-REQ-6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b");
    }

    @Test
    void cancel_processingOrRetryableRefusal_keepsRequestForRetry() {
        when(client.cancel(eq(101L), anyString(), anyString()))
                .thenReturn(cancellation("PROCESSING", 501L, null))
                .thenThrow(new DonWorryPaymentException(409, "PAYMENT_IN_PROGRESS"))
                .thenThrow(new DonWorryPaymentException(503, "CANCELLATION_NOT_READY"))
                .thenThrow(new DonWorryPaymentException(0, null));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gateway.cancel("DW-101", "고객 취소", "CANCEL-6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b"))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RECOVERY_PENDING));
        }
    }

    @Test
    void cancel_finalRefusal_isCancelFailed() {
        when(client.cancel(eq(101L), anyString(), anyString()))
                .thenThrow(new DonWorryPaymentException(409, "PAYMENT_NOT_CANCELABLE"));

        assertThatThrownBy(() -> gateway.cancel("DW-101", "고객 취소", "CANCEL-6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        assertThatThrownBy(() -> gateway.cancel("TOSS-KEY", "고객 취소", "key"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancellationKeyAndReason_followDonWorryRules() {
        assertThat(DonWorryPaymentGateway.cancellationKey("CANCEL-6F1C2A8E-3B4D-4E5F-8A9B-0C1D2E3F4A5B"))
                .isEqualTo("6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b");
        // UUID가 아닌 키는 항상 같은 UUID로 바꾼다(재시도 때 같은 키).
        assertThat(DonWorryPaymentGateway.cancellationKey("COMPENSATE-7"))
                .isEqualTo(DonWorryPaymentGateway.cancellationKey("COMPENSATE-7"))
                .matches("[0-9a-f-]{36}");
        assertThat(DonWorryPaymentGateway.cancellationReason("   ")).isEqualTo("고객 요청으로 주문 취소");
        assertThat(DonWorryPaymentGateway.cancellationReason("가".repeat(250)).codePointCount(0, 200))
                .isEqualTo(200);
    }

    private DonWorryCancellationResponse cancellation(String status, Long cancellationId, Instant completedAt) {
        return new DonWorryCancellationResponse("6f1c2a8e-3b4d-4e5f-8a9b-0c1d2e3f4a5b", 101L, status,
                cancellationId, "REFUND", 25_000L, Instant.parse("2026-10-31T03:00:00Z"),
                Instant.parse("2026-10-31T03:00:00Z"), completedAt, null, "NONE");
    }

    private DonWorryPaymentResponse response(String status) {
        return response(status, "CAPTURED".equals(status));
    }

    private DonWorryPaymentResponse response(String status, boolean authorized) {
        return new DonWorryPaymentResponse(101L, "ORD-100", 1, status,
                Instant.parse("2026-10-31T03:00:00Z"), Instant.parse("2026-10-31T03:01:00Z"), 25_000L, null,
                authorized);
    }
}
