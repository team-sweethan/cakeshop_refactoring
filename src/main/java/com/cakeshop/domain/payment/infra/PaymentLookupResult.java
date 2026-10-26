package com.cakeshop.domain.payment.infra;

import java.time.LocalDateTime;

public record PaymentLookupResult(
        String paymentKey,
        String orderId,
        String method,
        PaymentGatewayStatus status,
        long totalAmount,
        LocalDateTime approvedAt,
        CancellationResult cancellation
) {

    public ApprovalResult toApprovalResult() {
        return new ApprovalResult(
                paymentKey,
                orderId,
                method,
                status,
                totalAmount,
                approvedAt
        );
    }
}
