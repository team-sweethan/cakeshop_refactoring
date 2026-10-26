package com.cakeshop.domain.payment.infra;

/** PG 응답을 해석할 때 사용하며, DB에 저장하는 로컬 결제 상태와 구분한다. */
public enum PaymentGatewayStatus {
    READY,
    IN_PROGRESS,
    DONE,
    CANCELED,
    ABORTED,
    EXPIRED,
    // 현재 처리하지 않는 PG 상태는 기존의 기본 실패·복구 분기로 전달한다.
    UNKNOWN
}
