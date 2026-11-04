package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.service.payment.OrderPaymentCommandService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-04
 * 기능 : 돈워리 보류 뒤 직원 승인(CAPTURED) 반영 검증
 * 설명 : DonWorry ADR-0064 4절 — 확인 중을 한 번만 끝내고, 그때만 일반 주문을 픽업 준비로 넘긴다.
 * ******************************
 */
class DonWorryPaymentCaptureServiceTests {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 4, 12, 0);

    private final PaymentMapper paymentMapper = mock(PaymentMapper.class);
    private final OrderPaymentCommandService orders = mock(OrderPaymentCommandService.class);
    private final DonWorryPaymentCaptureService service = new DonWorryPaymentCaptureService(paymentMapper, orders);

    @Test
    void heldPayment_endsReviewAndReleasesOrder() {
        when(paymentMapper.markGatewayCaptured(1L, "DW-101", AT)).thenReturn(1);

        assertThat(service.applyCapture(payment(), AT)).isTrue();
        verify(orders).releaseGeneralOrderAfterGatewayCapture(5L, AT);
    }

    // 보류가 아니었거나(바로 매입) 웹훅·조회 배치가 이미 반영한 결제
    @Test
    void notHeldOrAlreadyCaptured_changesNothing() {
        when(paymentMapper.markGatewayCaptured(1L, "DW-101", AT)).thenReturn(0);

        assertThat(service.applyCapture(payment(), AT)).isFalse();
        verify(orders, never()).releaseGeneralOrderAfterGatewayCapture(anyLong(), any());
    }

    private static Payment payment() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrderId(5L);
        payment.setPaymentKey("DW-101");
        return payment;
    }
}
