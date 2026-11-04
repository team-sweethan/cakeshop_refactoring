package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.service.OrderNotificationService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.GatewayVoidHandling;
import com.cakeshop.domain.order.service.payment.OrderPaymentRecoveryService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.service.DonWorryPaymentVoidService.VoidOutcome;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-02
 * 기능 : 돈워리 보류 뒤 거절(VOIDED) 반영 검증
 * 설명 : DonWorry ADR-0064 4-1 — 이행 전이면 주문 취소, 이행이 시작됐으면 주문 유지(한 번만 표시), 끝난 주문은 그대로.
 * ******************************
 */
class DonWorryPaymentVoidServiceTests {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 2, 21, 0);

    private final PaymentMapper paymentMapper = mock(PaymentMapper.class);
    private final OrderPaymentRecoveryService recovery = mock(OrderPaymentRecoveryService.class);
    private final OrderNotificationService notifications = mock(OrderNotificationService.class);
    private final OrderPaymentQueryService orders = mock(OrderPaymentQueryService.class);
    private final DonWorryPaymentVoidService service =
            new DonWorryPaymentVoidService(paymentMapper, recovery, notifications, orders);

    @Test
    void beforeFulfillment_cancelsOrderOnce() {
        Payment payment = payment();
        when(orders.gatewayVoidHandling(5L)).thenReturn(Optional.of(GatewayVoidHandling.CANCEL_ORDER));
        when(paymentMapper.insertGatewayVoidCancellation(anyLong(), anyString(), anyString(), any()))
                .thenReturn(1)
                .thenReturn(0);
        when(paymentMapper.cancelPaymentByGatewayVoid(anyLong(), anyString(), any())).thenReturn(1);

        assertThat(service.applyVoid(payment, 10L, AT)).isEqualTo(VoidOutcome.ORDER_CANCELED);
        assertThat(service.applyVoid(payment, 10L, AT)).isEqualTo(VoidOutcome.UNCHANGED);
        verify(recovery).cancelAfterPaymentCompensation(5L, AT, DonWorryPaymentVoidService.REASON);
        verify(notifications).notifyOrderCanceled(5L, 10L);
    }

    @Test
    void fulfillmentStarted_keepsOrderAndMarksOnce() {
        Payment payment = payment();
        when(orders.gatewayVoidHandling(5L)).thenReturn(Optional.of(GatewayVoidHandling.KEEP_FULFILLED));
        when(paymentMapper.markGatewayVoidKept(1L, "DW-101")).thenReturn(1).thenReturn(0);

        assertThat(service.applyVoid(payment, 10L, AT)).isEqualTo(VoidOutcome.ORDER_KEPT);
        assertThat(service.applyVoid(payment, 10L, AT)).isEqualTo(VoidOutcome.UNCHANGED);
        verify(paymentMapper, never()).insertGatewayVoidCancellation(anyLong(), anyString(), anyString(), any());
        verify(recovery, never()).cancelAfterPaymentCompensation(anyLong(), any(), anyString());
        verify(notifications, never()).notifyOrderCanceled(anyLong(), anyLong());
    }

    @Test
    void finishedOrMissingOrder_isUnchanged() {
        Payment payment = payment();
        when(orders.gatewayVoidHandling(5L))
                .thenReturn(Optional.of(GatewayVoidHandling.NOTHING))
                .thenReturn(Optional.empty());

        assertThat(service.applyVoid(payment, 10L, AT)).isEqualTo(VoidOutcome.UNCHANGED);
        assertThat(service.applyVoid(payment, 10L, AT)).isEqualTo(VoidOutcome.UNCHANGED);
        verify(paymentMapper, never()).markGatewayVoidKept(anyLong(), anyString());
        verify(paymentMapper, never()).insertGatewayVoidCancellation(anyLong(), anyString(), anyString(), any());
    }

    private static Payment payment() {
        Payment payment = mock(Payment.class);
        when(payment.getId()).thenReturn(1L);
        when(payment.getOrderId()).thenReturn(5L);
        when(payment.getPaymentKey()).thenReturn("DW-101");
        return payment;
    }
}
