package com.cakeshop.domain.payment.infra;

import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TossPaymentGatewayTests {

    private final TossPaymentClient client = mock(TossPaymentClient.class);
    private final PaymentGateway gateway = new TossPaymentGateway(
            client, new TossPaymentAvailability("test-client-key", "test-secret-key")
    );

    @Test
    void checkoutSettings_tossCredentials_preservesAvailabilityAndPublicKey() {
        assertThat(gateway.clientKey()).isEqualTo("test-client-key");
        assertThat(gateway.isAvailable()).isTrue();
        PaymentGateway unavailable = new TossPaymentGateway(
                client, new TossPaymentAvailability("test-client-key", "")
        );
        assertThat(unavailable.isAvailable()).isFalse();
        assertThat(unavailable.clientKey()).isEqualTo("test-client-key");
    }

    @Test
    void approve_tossResult_preservesArgumentsAndResult() {
        ApprovalResult result = new ApprovalResult(
                "payment-key", "ORD-100", "카드", PaymentGatewayStatus.DONE,
                30_000, LocalDateTime.of(2026, 8, 1, 10, 0)
        );
        when(client.approve("payment-key", "ORD-100", 30_000, "approval-key"))
                .thenReturn(result);

        assertThat(gateway.approve("payment-key", "ORD-100", 30_000, "approval-key"))
                .isSameAs(result);
    }

    @Test
    void find_paymentNotFound_preservesEmptyResult() {
        when(client.find("missing-key")).thenReturn(Optional.empty());

        assertThat(gateway.find("missing-key")).isEmpty();
    }

    @Test
    void cancel_tossResult_preservesReasonIdempotencyKeyAndResult() {
        CancellationResult result = new CancellationResult(
                PaymentGatewayStatus.CANCELED, "transaction-key",
                LocalDateTime.of(2026, 8, 1, 10, 1)
        );
        when(client.cancel("payment-key", "단순 변심", "cancel-key")).thenReturn(result);

        assertThat(gateway.cancel("payment-key", "단순 변심", "cancel-key")).isSameAs(result);
    }

    @Test
    void find_tossFailure_propagatesForExistingRecoveryHandling() {
        BusinessException failure = new BusinessException(PaymentErrorCode.TOSS_APPROVAL_FAILED);
        when(client.find("payment-key")).thenThrow(failure);

        assertThatThrownBy(() -> gateway.find("payment-key")).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 100, 101})
    void formatOrderName_lengthBoundary_preservesExistingLimit(int length) {
        String orderName = "가".repeat(length);

        assertThat(gateway.formatOrderName(orderName))
                .isEqualTo(orderName.substring(0, Math.min(length, 100)));
    }

    @Test
    void failureCodes_knownTossCodes_classifiesWithoutMixingReasons() {
        assertThat(gateway.isUserCanceled("PAY_PROCESS_CANCELED")).isTrue();
        assertThat(gateway.isCardRejected("PAY_PROCESS_CANCELED")).isFalse();
        assertThat(gateway.isUserCanceled("REJECT_CARD_COMPANY")).isFalse();
        assertThat(gateway.isCardRejected("REJECT_CARD_COMPANY")).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"UNKNOWN_FAILURE", "pay_process_canceled"})
    void failureCodes_unrecognizedCode_usesExistingGenericFailure(String code) {
        assertThat(gateway.isUserCanceled(code)).isFalse();
        assertThat(gateway.isCardRejected(code)).isFalse();
    }

}
