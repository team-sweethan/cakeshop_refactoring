package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제 단건 응답
 * 설명 : paymentStatus는 DonWorry 상태 문자열을 그대로 둔다. CakeShop 상태로의 매핑은 OWN 게이트웨이 결정 후 정한다.
 *        PENDING·HELD는 아직 결과가 아니며 GET으로 다시 조회해야 한다.
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
        String declineCode
) {

    public boolean inProgress() {
        return "PENDING".equals(paymentStatus) || "HELD".equals(paymentStatus);
    }

    /** 청구 없이 끝난 시도. 이 상태면 같은 주문으로 새 시도나 다른 PG 결제를 해도 이중 청구가 없다. */
    public boolean endedWithoutCharge() {
        return "DENIED".equals(paymentStatus) || "DECLINED".equals(paymentStatus)
                || "VOIDED".equals(paymentStatus) || "EXPIRED".equals(paymentStatus);
    }
}
