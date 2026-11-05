package com.cakeshop.domain.payment.infra;

import java.time.LocalDateTime;

/**
 * PG 결제 조회 결과.
 *
 * @param heldForReview 승인 완료(DONE)이지만 PG가 매입을 보류하고 보안 검토 중인 결제. {@link ApprovalResult#heldForReview()} 참고
 */
public record PaymentLookupResult(
        String paymentKey,
        String orderId,
        String method,
        PaymentGatewayStatus status,
        long totalAmount,
        LocalDateTime approvedAt,
        CancellationResult cancellation,
        boolean heldForReview
) {

    /** 보류가 없는 PG(Toss 등)의 조회 결과 */
    public PaymentLookupResult(
            String paymentKey,
            String orderId,
            String method,
            PaymentGatewayStatus status,
            long totalAmount,
            LocalDateTime approvedAt,
            CancellationResult cancellation
    ) {
        this(paymentKey, orderId, method, status, totalAmount, approvedAt, cancellation, false);
    }

    public ApprovalResult toApprovalResult() {
        return new ApprovalResult(
                paymentKey,
                orderId,
                method,
                status,
                totalAmount,
                approvedAt,
                heldForReview
        );
    }
}
