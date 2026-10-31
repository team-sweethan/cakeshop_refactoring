package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 주문별 결제 시도 응답
 * 설명 : GET /api/payment-orders 응답 중 최신 시도만 쓴다. 과거 시도 목록은 받지 않는다.
 * ******************************
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DonWorryPaymentOrderResponse(
        String orderRef,
        DonWorryPaymentResponse latestPayment
) {
}
