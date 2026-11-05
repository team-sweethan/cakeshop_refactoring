package com.cakeshop.domain.payment.event;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-04
 * 기능 : PG 결제 확인 중(보류) 결제 완료 이벤트
 * 설명 : 결제는 승인됐지만 PG(돈워리)가 매입을 보류하고 확인 중인 결제를 완료한 트랜잭션 안에서 발행한다.
 *        주문 도메인이 커밋 뒤 관리자에게 "결제 확인 중" 알림을 보낸다(DonWorry ADR-0064 4절).
 * ******************************
 */
public record PaymentUnderGatewayReviewEvent(long orderId) {
}
