package com.cakeshop.domain.payment.entity;

// 로컬 결제 상태 — OrderStatus와 분리, 주문 enum에 섞지 않는다.
public enum PaymentStatus {
    READY,
    DONE,
    CANCELED,
    // PG가 반환할 수 있는 상태지만 부분 취소 업무 흐름은 지원 범위가 확정될 때 별도로 구현한다.
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED
}
