package com.cakeshop.domain.order.entity;

import java.util.Set;

// 결제 완료는 PaymentStatus.DONE으로 관리하고 주문에는 업무 처리 상태만 저장한다.
public enum OrderStatus {

    PENDING_PAYMENT("결제 대기"),   // 주문 생성 후 결제 대기
    // 수제 케이크 결제 완료 후 관리자 검토, 또는 PG(돈워리)가 결제를 확인 중인 일반 주문(DonWorry ADR-0064 4절)
    UNDER_REVIEW("주문 확인 중"),
    IN_PRODUCTION("제작 중"),       // 수제 케이크 제작 중
    READY_FOR_PICKUP("픽업 준비"),  // 일반 결제 완료 또는 수제 케이크 제작 완료 후 픽업 대기
    PICKED_UP("픽업 완료"),         // 픽업 완료 (최종)
    CANCELED("취소 완료"),          // 고객 또는 관리자 취소 및 환불 완료 (최종)
    REJECTED("주문 반려"),          // 수제 주문 반려 및 환불 완료 (최종)
    EXPIRED("결제 만료");           // 결제 기한 만료 (최종)

    private final String statusLabel;

    OrderStatus(String statusLabel) {
        this.statusLabel = statusLabel;
    }

    public String statusLabel() {
        return statusLabel;
    }

    // 주문 유형·취소 시각·권한 같은 추가 정책은 Service에서 검증한다.
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING_PAYMENT ->
                Set.of(UNDER_REVIEW, READY_FOR_PICKUP, CANCELED, EXPIRED).contains(next);
            // READY_FOR_PICKUP은 PG 결제 확인이 끝난 일반 주문만(수제는 IN_PRODUCTION을 거친다)
            case UNDER_REVIEW ->
                Set.of(IN_PRODUCTION, READY_FOR_PICKUP, REJECTED, CANCELED).contains(next);
            case IN_PRODUCTION -> next == READY_FOR_PICKUP;
            case READY_FOR_PICKUP ->
                Set.of(PICKED_UP, CANCELED).contains(next);
            case PICKED_UP, CANCELED, REJECTED, EXPIRED -> false;
        };
    }

    public boolean isFinal() {
        return this == REJECTED || this == PICKED_UP || this == CANCELED || this == EXPIRED;
    }
}
