package com.cakeshop.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OrderStatusTests {

    @Test
    void definesOnlyDatabaseOrderStatuses() {
        assertThat(Set.of(OrderStatus.values()))
            .containsExactlyInAnyOrder(
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.UNDER_REVIEW,
                OrderStatus.IN_PRODUCTION,
                OrderStatus.READY_FOR_PICKUP,
                OrderStatus.PICKED_UP,
                OrderStatus.CANCELED,
                OrderStatus.REJECTED,
                OrderStatus.EXPIRED
            );
    }

    @Test
    void providesStatusLabelsInOnePlace() {
        assertThat(Map.of(
                OrderStatus.PENDING_PAYMENT, "결제 대기",
                OrderStatus.UNDER_REVIEW, "주문 확인 중",
                OrderStatus.IN_PRODUCTION, "제작 중",
                OrderStatus.READY_FOR_PICKUP, "픽업 준비",
                OrderStatus.PICKED_UP, "픽업 완료",
                OrderStatus.CANCELED, "취소 완료",
                OrderStatus.REJECTED, "주문 반려",
                OrderStatus.EXPIRED, "결제 만료"
        )).allSatisfy((status, label) -> assertThat(status.statusLabel()).isEqualTo(label));
    }

    @Test
    void allowsOnlyDefinedTransitions() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.UNDER_REVIEW)).isTrue();
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.READY_FOR_PICKUP)).isTrue();
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.CANCELED)).isTrue();

        assertThat(OrderStatus.UNDER_REVIEW.canTransitionTo(OrderStatus.IN_PRODUCTION)).isTrue();
        assertThat(OrderStatus.UNDER_REVIEW.canTransitionTo(OrderStatus.REJECTED)).isTrue();
        assertThat(OrderStatus.UNDER_REVIEW.canTransitionTo(OrderStatus.CANCELED)).isTrue();
        // PG 결제 확인이 끝난 일반 주문 (DonWorry ADR-0064 4절)
        assertThat(OrderStatus.UNDER_REVIEW.canTransitionTo(OrderStatus.READY_FOR_PICKUP)).isTrue();

        assertThat(OrderStatus.IN_PRODUCTION.canTransitionTo(OrderStatus.READY_FOR_PICKUP)).isTrue();

        assertThat(OrderStatus.READY_FOR_PICKUP.canTransitionTo(OrderStatus.PICKED_UP)).isTrue();
        assertThat(OrderStatus.READY_FOR_PICKUP.canTransitionTo(OrderStatus.CANCELED)).isTrue();

        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PICKED_UP)).isFalse();
        assertThat(OrderStatus.UNDER_REVIEW.canTransitionTo(OrderStatus.PICKED_UP)).isFalse();
        assertThat(OrderStatus.IN_PRODUCTION.canTransitionTo(OrderStatus.PICKED_UP)).isFalse();
        assertThat(OrderStatus.IN_PRODUCTION.canTransitionTo(OrderStatus.CANCELED)).isFalse();
        assertThat(OrderStatus.IN_PRODUCTION.canTransitionTo(OrderStatus.REJECTED)).isFalse();
        assertThat(OrderStatus.READY_FOR_PICKUP.canTransitionTo(OrderStatus.REJECTED)).isFalse();
    }

    @Test
    void finalStatusesCannotTransition() {
        Set<OrderStatus> finalStatuses = Set.of(
            OrderStatus.PICKED_UP,
            OrderStatus.CANCELED,
            OrderStatus.REJECTED,
            OrderStatus.EXPIRED
        );

        assertThat(finalStatuses).allSatisfy(status -> {
            assertThat(status.isFinal()).isTrue();
            assertThat(Set.of(OrderStatus.values()))
                .noneMatch(status::canTransitionTo);
        });

        assertThat(Set.of(
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.UNDER_REVIEW,
            OrderStatus.IN_PRODUCTION,
            OrderStatus.READY_FOR_PICKUP
        )).allSatisfy(status -> assertThat(status.isFinal()).isFalse());
    }
}
