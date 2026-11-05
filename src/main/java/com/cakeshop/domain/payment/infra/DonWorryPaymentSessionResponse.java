package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : 돈워리 결제 준비 응답
 * 설명 : checkoutUrl을 구매자 브라우저의 모달 iframe으로 연다.
 * ******************************
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DonWorryPaymentSessionResponse(String sessionKey, String checkoutUrl, Instant expiresAt) {
}
