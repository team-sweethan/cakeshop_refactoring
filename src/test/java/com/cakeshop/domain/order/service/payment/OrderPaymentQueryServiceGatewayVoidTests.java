package com.cakeshop.domain.order.service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.GatewayVoidHandling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (order)
 * 작성일 : 2026-11-02
 * 기능 : 돈워리 보류 뒤 거절(VOIDED) 때 주문 처리 판단 검증
 * 설명 : DonWorry ADR-0064 4-1 — 이행 전이면 주문 취소, 이행(제작)이 시작됐으면 유지, 이미 끝난 주문은 할 일 없음.
 * ******************************
 */
class OrderPaymentQueryServiceGatewayVoidTests {

    // 이행 전: 승인 대기 · 결제 대기 · 만료 보상 주문은 유형과 관계없이 취소한다
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "EXPIRED", "UNDER_REVIEW"})
    void beforeFulfillment_cancelsOrder(OrderStatus status) {
        for (OrderType type : OrderType.values()) {
            assertThat(OrderPaymentQueryService.gatewayVoidHandling(status, type))
                    .as(status + "/" + type)
                    .isEqualTo(GatewayVoidHandling.CANCEL_ORDER);
        }
    }

    // 픽업 준비는 일반 상품이면 아직 나가지 않은 재고라 취소하고, 수제 케이크면 제작이 끝난 뒤라 유지한다
    @Test
    void readyForPickup_dependsOnOrderType() {
        assertThat(OrderPaymentQueryService.gatewayVoidHandling(OrderStatus.READY_FOR_PICKUP, OrderType.GENERAL))
                .isEqualTo(GatewayVoidHandling.CANCEL_ORDER);
        assertThat(OrderPaymentQueryService.gatewayVoidHandling(OrderStatus.READY_FOR_PICKUP, OrderType.CUSTOM))
                .isEqualTo(GatewayVoidHandling.KEEP_FULFILLED);
    }

    // 제작 중 · 픽업 완료는 이미 이행됐으므로 주문을 유지한다(손실은 PG 부담)
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"IN_PRODUCTION", "PICKED_UP"})
    void fulfillmentStarted_keepsOrder(OrderStatus status) {
        for (OrderType type : OrderType.values()) {
            assertThat(OrderPaymentQueryService.gatewayVoidHandling(status, type))
                    .as(status + "/" + type)
                    .isEqualTo(GatewayVoidHandling.KEEP_FULFILLED);
        }
    }

    // 이미 취소 · 반려된 주문은 할 일이 없다
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCELED", "REJECTED"})
    void finishedOrder_nothing(OrderStatus status) {
        assertThat(OrderPaymentQueryService.gatewayVoidHandling(status, OrderType.GENERAL))
                .isEqualTo(GatewayVoidHandling.NOTHING);
    }
}
