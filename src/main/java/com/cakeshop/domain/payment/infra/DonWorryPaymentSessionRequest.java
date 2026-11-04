package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : 돈워리 결제 준비(POST /api/payment-sessions) 요청 본문
 * 설명 : 금액·주문번호·고객 참조·주문 시각 규칙은 기존 결제 요청과 같다. 카드·기기·IP는 보내지 않고 돈워리 결제창이 직접 받는다.
 * ******************************
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DonWorryPaymentSessionRequest(
        String orderRef,
        String customerRef,
        long amount,
        Instant orderCreatedAt,
        String orderName,
        String buyerEmail,
        String successUrl,
        String failUrl,
        String notifyUrl
) {

    public DonWorryPaymentSessionRequest {
        // DonWorry는 마이크로초보다 정밀한 시각을 거절한다.
        if (orderCreatedAt != null) {
            orderCreatedAt = orderCreatedAt.truncatedTo(ChronoUnit.MICROS);
        }
        if (orderName != null && orderName.length() > 100) {
            orderName = orderName.substring(0, 100);
        }
    }
}
