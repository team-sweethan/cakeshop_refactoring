package com.cakeshop.domain.order.service.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.member.service.MemberOrderQueryService;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.global.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCustomOrderServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T06:00:00Z"), ZoneId.of("Asia/Seoul")
    );

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private MemberOrderQueryService memberOrderQueryService;

    private AdminCustomOrderService service;

    @BeforeEach
    void setUp() {
        service = new AdminCustomOrderService(orderMapper, memberOrderQueryService, CLOCK);
        when(memberOrderQueryService.isActiveAdmin(7L)).thenReturn(true);
    }

    @Test
    void startProduction_underReviewCustomOrder_recordsApproval() {
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(custom(OrderStatus.UNDER_REVIEW)));
        when(orderMapper.startProductionIfUnderReview(10L, 7L, NOW)).thenReturn(1);

        service.startProduction(10L, 7L);

        verify(orderMapper).startProductionIfUnderReview(10L, 7L, NOW);
    }

    @Test
    void startProduction_underReviewOrder_startsDespiteDelayedApproval() {
        Order order = custom(OrderStatus.UNDER_REVIEW);
        order.setPickupAt(NOW.plusDays(2).minusSeconds(1));
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(orderMapper.startProductionIfUnderReview(10L, 7L, NOW)).thenReturn(1);

        service.startProduction(10L, 7L);

        verify(orderMapper).startProductionIfUnderReview(10L, 7L, NOW);
    }

    @Test
    void completeProduction_underReviewOrder_rejectsDirectSkip() {
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(custom(OrderStatus.UNDER_REVIEW)));

        assertThatThrownBy(() -> service.completeProduction(10L, 7L))
                .isInstanceOf(BusinessException.class);
    }

    // PG(돈워리)가 결제를 확인 중인 수제 주문은 확인 창 동의가 있어야 제작을 시작하고, 처음 진행을 기록한다
    @Test
    void startProduction_paymentUnderReview_requiresAcknowledgementAndRecordsProceed() {
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(custom(OrderStatus.UNDER_REVIEW)));
        when(orderMapper.hasPaymentUnderGatewayReview(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.startProduction(10L, 7L, false))
                .isInstanceOfSatisfying(BusinessException.class, error -> org.assertj.core.api.Assertions
                        .assertThat(error.getErrorCode())
                        .isEqualTo(com.cakeshop.domain.order.error.OrderErrorCode.PAYMENT_UNDER_REVIEW_CONFIRM_REQUIRED));
        verify(orderMapper, never()).startProductionIfUnderReview(10L, 7L, NOW);

        when(orderMapper.startProductionIfUnderReview(10L, 7L, NOW)).thenReturn(1);
        service.startProduction(10L, 7L, true);

        verify(orderMapper).recordGatewayReviewProceed(10L, 7L, NOW);
        verify(orderMapper).startProductionIfUnderReview(10L, 7L, NOW);
    }

    @Test
    void startProduction_suspendedOrDemotedAdmin_rejectsBeforeLockingOrder() {
        when(memberOrderQueryService.isActiveAdmin(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.startProduction(10L, 7L))
                .isInstanceOf(BusinessException.class);

        verify(orderMapper, never()).findOrderByIdForUpdate(10L);
    }

    private Order custom(OrderStatus status) {
        Order order = new Order();
        order.setId(10L);
        order.setOrderType(OrderType.CUSTOM);
        order.setStatus(status);
        order.setPickupAt(NOW.plusDays(2));
        return order;
    }
}
