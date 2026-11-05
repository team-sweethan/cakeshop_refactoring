package com.cakeshop.domain.order.dto;

/**
 * 주문 상태가 서버에서 바뀌었음을 열려 있는 화면에 알리기 위한 내부 이벤트다.
 * 돈워리가 보류 결제를 승인(픽업 준비) · 거절(주문 취소)해 웹훅 · 조회 배치로 상태가 바뀔 때 발행한다.
 * 화면은 주문 번호만 받고 자기 화면을 다시 불러와 바뀐 부분을 갈아 끼운다(order-live-status.js).
 */
public record OrderLiveStatusEvent(long orderId) {
}
