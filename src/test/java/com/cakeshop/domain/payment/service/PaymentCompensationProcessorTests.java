package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;

import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.TossPaymentClient;
import com.cakeshop.domain.payment.infra.TossPaymentGateway;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCompensationProcessorTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 10, 0);

    @Mock
    private PaymentRecoveryService paymentRecoveryService;

    @Mock
    private TossPaymentClient tossPaymentClient;

    private PaymentCompensationProcessor compensationProcessor;

    @BeforeEach
    void setUp() {
        compensationProcessor = new PaymentCompensationProcessor(
                paymentRecoveryService,
                new PaymentGatewayRouter("toss", List.of(new TossPaymentGateway(tossPaymentClient, null))),
                new PaymentApprovalResolver(new PaymentGatewayRouter("toss", List.of(new TossPaymentGateway(tossPaymentClient, null))))
        );
    }

    @Test
    void recoverPendingCompensations_doneRequest_retriesSameCancelKey() {
        CompensationRequest request = request();
        CancellationResult cancellation = cancellation();
        when(paymentRecoveryService.getPreparedCompensations(50)).thenReturn(List.of(request));
        when(tossPaymentClient.find(request.paymentKey())).thenReturn(Optional.of(
                new PaymentLookupResult(
                        request.paymentKey(), "ORD-100", "카드", PaymentGatewayStatus.DONE, 30_000L, NOW, null
                )
        ));
        when(tossPaymentClient.cancel(request.paymentKey(), request.reason(), request.idempotencyKey()))
                .thenReturn(cancellation);

        compensationProcessor.recoverPendingCompensations(50);

        verify(paymentRecoveryService).completeCompensation(request, cancellation);
    }

    @Test
    void recoverPendingCompensations_unapprovedRequest_releasesCompensation() {
        CompensationRequest request = request();
        when(paymentRecoveryService.getPreparedCompensations(50)).thenReturn(List.of(request));
        when(tossPaymentClient.find(request.paymentKey())).thenReturn(Optional.empty());

        compensationProcessor.recoverPendingCompensations(50);

        verify(paymentRecoveryService).releaseUnapprovedCompensation(request);
        verify(tossPaymentClient, never()).cancel(
                request.paymentKey(), request.reason(), request.idempotencyKey()
        );
    }

    @Test
    void recoverPendingCompensations_expiredRequest_releasesCompensation() {
        CompensationRequest request = request();
        when(paymentRecoveryService.getPreparedCompensations(50)).thenReturn(List.of(request));
        when(tossPaymentClient.find(request.paymentKey())).thenReturn(Optional.of(
                new PaymentLookupResult(
                        request.paymentKey(), "ORD-100", null, PaymentGatewayStatus.EXPIRED, 30_000L, null, null
                )
        ));

        compensationProcessor.recoverPendingCompensations(50);

        verify(paymentRecoveryService).releaseUnapprovedCompensation(request);
        verify(tossPaymentClient, never()).cancel(
                request.paymentKey(), request.reason(), request.idempotencyKey()
        );
    }

    @Test
    void recoverPendingCompensations_inProgressRequest_keepsCompensation() {
        CompensationRequest request = request();
        when(paymentRecoveryService.getPreparedCompensations(50)).thenReturn(List.of(request));
        when(tossPaymentClient.find(request.paymentKey())).thenReturn(Optional.of(
                new PaymentLookupResult(
                        request.paymentKey(), "ORD-100", "카드", PaymentGatewayStatus.IN_PROGRESS, 30_000L, null, null
                )
        ));

        compensationProcessor.recoverPendingCompensations(50);

        verify(paymentRecoveryService, never()).releaseUnapprovedCompensation(request);
        verify(tossPaymentClient, never()).cancel(
                request.paymentKey(), request.reason(), request.idempotencyKey()
        );
    }

    private CompensationRequest request() {
        return new CompensationRequest(
                PaymentProvider.TOSS,
                20L,
                1L,
                "payment-key",
                "COMPENSATE-20",
                BigDecimal.valueOf(30_000),
                "내부 주문 처리 실패로 인한 자동 결제 취소"
        );
    }

    private CancellationResult cancellation() {
        return new CancellationResult(PaymentGatewayStatus.CANCELED, "cancel-transaction", NOW.plusSeconds(20));
    }
}
