package com.cakeshop.domain.payment.infra;

import java.time.LocalDateTime;

/**
 * PG 승인 결과.
 *
 * @param heldForReview 카드 승인은 끝났지만 PG가 매입을 보류하고 보안 검토 중인 결제(돈워리 HELD).
 *                      구매자에게는 결제 완료이고, 가맹점은 판정이 날 때까지 "결제 확인 중"으로 본다(DonWorry ADR-0064 4절).
 */
public record ApprovalResult(
        String paymentKey,
        String orderId,
        String method,
        PaymentGatewayStatus status,
        long totalAmount,
        LocalDateTime approvedAt,
        boolean heldForReview
) {

    /** 보류가 없는 PG(Toss 등)의 승인 결과 */
    public ApprovalResult(
            String paymentKey,
            String orderId,
            String method,
            PaymentGatewayStatus status,
            long totalAmount,
            LocalDateTime approvedAt
    ) {
        this(paymentKey, orderId, method, status, totalAmount, approvedAt, false);
    }
}
