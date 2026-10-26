package com.cakeshop.domain.payment.dto.view;

/** PG 인증 실패·취소 화면에 노출할 안전한 안내 정보다. */
public record PaymentFailureView(
        long orderId,
        String message,
        boolean retryAvailable
) {
}
