package com.cakeshop.domain.payment.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 기존 Toss 클라이언트와 설정을 공통 PG 경계에 연결한다. */
@Component
@RequiredArgsConstructor
public class TossPaymentGateway implements PaymentGateway {

    private static final int ORDER_NAME_MAX_LENGTH = 100;

    private final TossPaymentClient tossPaymentClient;
    private final TossPaymentAvailability tossPaymentAvailability;

    @Override
    public boolean isAvailable() {
        return tossPaymentAvailability.isEnabled();
    }

    @Override
    public String clientKey() {
        return tossPaymentAvailability.clientKey();
    }

    @Override
    public String formatOrderName(String orderName) {
        if (orderName.length() <= ORDER_NAME_MAX_LENGTH) {
            return orderName;
        }
        return orderName.substring(0, ORDER_NAME_MAX_LENGTH);
    }

    @Override
    public boolean isUserCanceled(String failureCode) {
        return "PAY_PROCESS_CANCELED".equals(failureCode);
    }

    @Override
    public boolean isCardRejected(String failureCode) {
        return "REJECT_CARD_COMPANY".equals(failureCode);
    }

    @Override
    public ApprovalResult approve(String paymentKey, String orderId, long amount, String idempotencyKey) {
        return tossPaymentClient.approve(paymentKey, orderId, amount, idempotencyKey);
    }

    @Override
    public Optional<PaymentLookupResult> find(String paymentKey) {
        return tossPaymentClient.find(paymentKey);
    }

    @Override
    public CancellationResult cancel(String paymentKey, String reason, String idempotencyKey) {
        return tossPaymentClient.cancel(paymentKey, reason, idempotencyKey);
    }
}
