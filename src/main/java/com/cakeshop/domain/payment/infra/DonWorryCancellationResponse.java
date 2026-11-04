package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-02
 * 기능 : DonWorry 전액 취소 응답 (POST /api/payments/{paymentId}/cancellations)
 * 설명 : requestStatus가 SUCCEEDED·ALREADY_CANCELED일 때만 취소 완료다.
 *        PROCESSING·UNKNOWN은 접수됨(결과 대기), 나머지는 오류 응답으로 온다. 돈워리 계약 #381.
 * ******************************
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DonWorryCancellationResponse(
        String requestKey,
        long paymentId,
        String requestStatus,
        Long cancellationId,
        String operation,
        Long amount,
        Instant requestedAt,
        Instant updatedAt,
        Instant completedAt,
        String code,
        String nextAction
) {

    public boolean completed() {
        return "SUCCEEDED".equals(requestStatus) || "ALREADY_CANCELED".equals(requestStatus);
    }
}
