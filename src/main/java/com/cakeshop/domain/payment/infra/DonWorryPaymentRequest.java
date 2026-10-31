package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry POST /api/payments 요청 본문
 * 설명 : DonWorry #149 계약을 따른다. FDS 식별자(#325)는 선택값이며 null이면 보내지 않는다.
 *        cardToken은 카드번호 원문이 아닌 토큰·해시, cardDisplayLabel은 마스킹한 값만 넣는다.
 * ******************************
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DonWorryPaymentRequest(
        String orderRef,
        String customerRef,
        long amount,
        Instant orderCreatedAt,
        String deviceId,
        String cardToken,
        String cardDisplayLabel,
        String ipCountryCode,
        String ipAddress,
        String buyerEmail
) {

    public DonWorryPaymentRequest {
        // DonWorry는 마이크로초보다 정밀한 시각을 거절한다.
        if (orderCreatedAt != null) {
            orderCreatedAt = orderCreatedAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public static DonWorryPaymentRequest of(String orderRef, long memberId, long amount, Instant orderCreatedAt) {
        return new DonWorryPaymentRequest(orderRef, Long.toString(memberId), amount, orderCreatedAt,
                null, null, null, null, null, null);
    }
}
