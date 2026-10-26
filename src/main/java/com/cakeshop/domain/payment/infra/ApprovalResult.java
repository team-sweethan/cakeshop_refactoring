package com.cakeshop.domain.payment.infra;

import java.time.LocalDateTime;

public record ApprovalResult(
        String paymentKey,
        String orderId,
        String method,
        PaymentGatewayStatus status,
        long totalAmount,
        LocalDateTime approvedAt
) {
}
