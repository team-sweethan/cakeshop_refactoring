package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import com.cakeshop.domain.payment.service.RefundService.RefundRequest;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class PaymentProviderRoutingTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @ParameterizedTest
    @CsvSource({"own, TOSS", "toss, OWN"})
    void operations_selectionChanged_useRecordedProviderForApprovalRefundAndRecovery(
            String selected, PaymentProvider recorded) {
        PaymentGateway toss = mock(PaymentGateway.class);
        PaymentGateway own = mock(PaymentGateway.class);
        when(toss.provider()).thenReturn(PaymentProvider.TOSS);
        when(own.provider()).thenReturn(PaymentProvider.OWN);
        PaymentGatewayRouter router = new PaymentGatewayRouter(selected, List.of(toss, own));
        PaymentGateway expected = recorded == PaymentProvider.TOSS ? toss : own;
        PaymentGateway other = recorded == PaymentProvider.TOSS ? own : toss;
        Payment payment = new Payment();
        payment.setId(20L);
        payment.setOrderId(10L);
        payment.setProvider(recorded);
        payment.setGatewayOrderId("ORD-100");
        payment.setPaymentKey("payment-key");
        payment.setIdempotencyKey("PAY-20");
        payment.setAmount(BigDecimal.valueOf(30_000));
        PaymentLookupResult done = new PaymentLookupResult("payment-key", "ORD-100", "CARD",
                PaymentGatewayStatus.DONE, 30_000, NOW, null);
        when(expected.find("payment-key")).thenReturn(Optional.of(done));
        when(expected.approve("payment-key", "ORD-100", 30_000, "PAY-20")).thenReturn(done.toApprovalResult());
        PaymentApprovalResolver resolver = new PaymentApprovalResolver(router);
        PaymentConfirmForm form = new PaymentConfirmForm("payment-key", "ORD-100", BigDecimal.valueOf(30_000));
        assertThat(resolver.resolveCurrent(payment, form).state()).isEqualTo(PaymentApprovalResolver.ApprovalState.DONE);
        assertThat(resolver.approve(payment, form).approval()).isEqualTo(done.toApprovalResult());

        RefundService refunds = mock(RefundService.class);
        RefundRequest refund = new RefundRequest(recorded, 30L, 10L, "payment-key", "CANCEL-20", "고객 취소", "CUSTOMER", NOW);
        CancellationResult canceled = new CancellationResult(PaymentGatewayStatus.CANCELED, "TX-20", NOW);
        when(refunds.prepareCustomerCancellation(1L, 10L, "고객 취소")).thenReturn(refund);
        when(expected.cancel("payment-key", "고객 취소", "CANCEL-20")).thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        when(expected.find("payment-key")).thenReturn(Optional.of(new PaymentLookupResult(
                "payment-key", "ORD-100", "CARD", PaymentGatewayStatus.CANCELED, 30_000, NOW, canceled)));
        RefundFacade facade = new RefundFacade(refunds, router);
        facade.cancelCustomerOrder(1L, 10L, "고객 취소");
        verify(refunds).completeCancellation(refund, canceled);
        // 재기동 후 조회한 미완료 환불도 원 결제의 PG를 전달한다.
        when(refunds.getRequestedCancellations(10)).thenReturn(List.of(refund));
        facade.recoverPendingCancellations(10);
        verify(refunds, org.mockito.Mockito.times(2)).completeCancellation(refund, canceled);

        PaymentRecoveryService recovery = mock(PaymentRecoveryService.class);
        CompensationRequest compensation = new CompensationRequest(recorded, 20L, 10L,
                "payment-key", "COMPENSATE-20", BigDecimal.valueOf(30_000), "보상 취소");
        when(recovery.getPreparedCompensations(10)).thenReturn(List.of(compensation));
        when(expected.cancel("payment-key", "보상 취소", "COMPENSATE-20"))
                .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        new PaymentCompensationProcessor(recovery, router, resolver).recoverPendingCompensations(10);
        verify(recovery).completeCompensation(compensation, canceled);
        verify(other, never()).find(anyString());
        verify(other, never()).cancel(anyString(), anyString(), anyString());
        verify(other, never()).approve(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void forProvider_missingProviderOrAdapter_neverFallsBackToSelectedGateway() {
        PaymentGateway toss = mock(PaymentGateway.class);
        when(toss.provider()).thenReturn(PaymentProvider.TOSS);
        PaymentGatewayRouter router = new PaymentGatewayRouter("toss", List.of(toss));
        for (PaymentProvider unavailable : new PaymentProvider[]{null, PaymentProvider.OWN}) {
            assertThatThrownBy(() -> router.forProvider(unavailable)).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
        }
        verify(toss, never()).find(anyString());
    }

    @ParameterizedTest
    @EnumSource(PaymentProvider.class)
    void createRequest_persistedPayment_keepsProviderForRecovery(PaymentProvider provider) {
        Payment payment = new Payment();
        payment.setProvider(provider);
        payment.setId(20L);
        payment.setOrderId(10L);
        payment.setAmount(BigDecimal.valueOf(30_000));
        CompensationRequest request = new PaymentRecoveryService(null, null).createRequest(payment, "payment-key");
        assertThat(request.provider()).isEqualTo(provider);
    }
}
