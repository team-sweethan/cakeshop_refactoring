package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.dto.view.OrderPaymentAdminView;
import com.cakeshop.domain.order.service.payment.OrderPaymentAdminQueryService;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.dto.form.PaymentAdminSearchCondition;
import com.cakeshop.domain.payment.dto.view.PaymentAdminPaymentRow;
import com.cakeshop.domain.payment.dto.view.PaymentAdminSummaryView;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentAdminServiceTests {

    @Test
    void getPayments_selectedStatus_combinesPaymentAndOrderContracts() {
        PaymentMapper paymentMapper = Mockito.mock(PaymentMapper.class);
        OrderPaymentAdminQueryService orderService = Mockito.mock(OrderPaymentAdminQueryService.class);
        PaymentAdminService service = new PaymentAdminService(paymentMapper, orderService);
        PaymentAdminSearchCondition condition = new PaymentAdminSearchCondition();
        condition.setStatus(PaymentStatus.CANCELED);
        PaymentAdminPaymentRow payment = new PaymentAdminPaymentRow(
                1L, 10L, "ORD-10", BigDecimal.valueOf(30_000), "CARD", PaymentStatus.CANCELED,
                null, null, null, null, null, null
        );
        when(paymentMapper.summarizePaymentsForAdmin()).thenReturn(new PaymentAdminSummaryView(1, 0, 1, 0));
        when(paymentMapper.findPaymentsForAdmin(PaymentStatus.CANCELED)).thenReturn(List.of(payment));
        when(orderService.getPaymentAdminOrders(List.of(10L))).thenReturn(List.of(
                new OrderPaymentAdminView(10L, "ORD-10", "홍길동", "GENERAL")
        ));

        var result = service.getPayments(condition);

        assertThat(result.payments()).singleElement().satisfies(row -> {
            assertThat(row.orderNumber()).isEqualTo("ORD-10");
            assertThat(row.ordererName()).isEqualTo("홍길동");
            assertThat(row.orderTypeLabel()).isEqualTo("일반 상품");
        });
        verify(orderService).getPaymentAdminOrders(List.of(10L));
    }

    @Test
    void updateExpirationCheck_expiredPayment_recordsRequestedCheckState() {
        PaymentMapper paymentMapper = Mockito.mock(PaymentMapper.class);
        OrderPaymentAdminQueryService orderService = Mockito.mock(OrderPaymentAdminQueryService.class);
        PaymentAdminService service = new PaymentAdminService(paymentMapper, orderService);
        Payment payment = new Payment();
        payment.setProvider(PaymentProvider.TOSS);
        payment.setStatus(PaymentStatus.EXPIRED);
        when(paymentMapper.findPaymentById(1L)).thenReturn(Optional.of(payment));

        service.updateExpirationCheck(1L, true);
        service.updateExpirationCheck(1L, false);

        verify(paymentMapper).markExpirationCheckedIfExpired(1L);
        verify(paymentMapper).clearExpirationCheckedIfExpired(1L);
    }
}
