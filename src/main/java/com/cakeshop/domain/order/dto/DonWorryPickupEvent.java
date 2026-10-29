package com.cakeshop.domain.order.dto;

/** 커밋된 주문만 외부로 알리기 위한 앱 내부 이벤트다. */
/**
 * ******************************
 * 작성자 : 민정
 * 담당자 : 주환 (order), 민정 (P6 연동)
 * 작성일 : 2026-10-29
 * 기능 : 픽업 전송 내부 이벤트
 * 설명 : 주문 원 트랜잭션에서 발행하고 커밋 후에만 처리한다.
 * ******************************
 */
public record DonWorryPickupEvent(long orderId, boolean completion) {
}
