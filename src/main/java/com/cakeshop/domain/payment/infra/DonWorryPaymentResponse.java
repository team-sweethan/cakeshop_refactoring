package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제 단건 응답
 * 설명 : paymentStatus는 DonWorry 상태 문자열을 그대로 둔다.
 *        authorized = 카드 승인 완료. 승인되면(매입 전 PENDING·보류 HELD 포함) CakeShop은 결제 완료로 본다.
 *        보류(HELD)는 구매자에게 결제 완료지만 가맹점에는 "결제 확인 중"이다(DonWorry ADR-0064 4절).
 *        직원이 승인하면 CAPTURED, 거절하면 VOIDED가 되고 거절이면 주문을 취소한다.
 * ******************************
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DonWorryPaymentResponse(
        long paymentId,
        String orderRef,
        int attemptNo,
        String paymentStatus,
        Instant requestedAt,
        Instant statusChangedAt,
        long amount,
        String declineCode,
        // 승인 여부가 없는 이전 응답은 승인 전(null)으로 본다.
        Boolean authorized
) {

    public boolean inProgress() {
        return "PENDING".equals(paymentStatus) || "HELD".equals(paymentStatus);
    }

    /** 카드 승인이 끝나 청구가 걸린 결제. 매입 완료·매입 대기·보류를 포함한다. */
    public boolean approved() {
        return Boolean.TRUE.equals(authorized) && ("PENDING".equals(paymentStatus) || "HELD".equals(paymentStatus)
                || "CAPTURED".equals(paymentStatus));
    }

    /** 승인됐지만 매입이 보류돼 돈워리 보안 직원 판정을 기다리는 결제 */
    public boolean heldForReview() {
        return Boolean.TRUE.equals(authorized) && "HELD".equals(paymentStatus);
    }

    /** 직원 승인 등으로 매입까지 끝난 결제 */
    public boolean captured() {
        return Boolean.TRUE.equals(authorized) && "CAPTURED".equals(paymentStatus);
    }

    /** 청구 없이 끝난 시도. 이 상태면 같은 주문으로 새 시도나 다른 PG 결제를 해도 이중 청구가 없다. */
    public boolean endedWithoutCharge() {
        return "DENIED".equals(paymentStatus) || "DECLINED".equals(paymentStatus)
                || "VOIDED".equals(paymentStatus) || "EXPIRED".equals(paymentStatus);
    }
}
