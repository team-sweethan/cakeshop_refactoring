package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.TossPaymentClient;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.service.RefundService.RefundRequest;
import com.cakeshop.global.error.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import com.cakeshop.domain.payment.infra.TossPaymentGateway;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundFacadeTests {

    @Mock
    private RefundService refundService;

    @Mock
    private TossPaymentClient tossPaymentClient;

    private RefundFacade refundFacade;

    @BeforeEach
    void setUp() {
        refundFacade = new RefundFacade(refundService, new PaymentGatewayRouter("toss", List.of(new TossPaymentGateway(tossPaymentClient, null))));
    }

    @Test
    void cancelCustomerOrder_tossSuccess_completesInternalCancellation() {
        RefundRequest request = request();
        CancellationResult result = new CancellationResult(
                PaymentGatewayStatus.CANCELED, "transaction-key", request.requestedAt().plusSeconds(1));
        when(refundService.prepareCustomerCancellation(3L, 10L, "단순 변심")).thenReturn(request);
        when(tossPaymentClient.cancel("payment-key", "단순 변심", "idempotency-key"))
                .thenReturn(result);

        refundFacade.cancelCustomerOrder(3L, 10L, "단순 변심");

        verify(refundService).completeCancellation(request, result);
    }

    @Test
    void cancelCustomerOrder_zeroAmount_completesWithoutTossCancellation() {
        when(refundService.cancelCustomerZeroAmountOrder(3L, 10L, "단순 변심")).thenReturn(true);

        refundFacade.cancelCustomerOrder(3L, 10L, "단순 변심");

        verify(refundService, never()).prepareCustomerCancellation(3L, 10L, "단순 변심");
        verify(tossPaymentClient, never()).cancel(any(), any(), any());
    }

    @Test
    void cancelCustomerOrder_tossAndLookupFailure_keepsRequestRequested() {
        RefundRequest request = request();
        when(refundService.prepareCustomerCancellation(3L, 10L, "단순 변심")).thenReturn(request);
        when(tossPaymentClient.cancel("payment-key", "단순 변심", "idempotency-key"))
                .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        when(tossPaymentClient.find("payment-key"))
                .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED));

        assertThatThrownBy(() -> refundFacade.cancelCustomerOrder(3L, 10L, "단순 변심"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.PAYMENT_RECOVERY_PENDING)
                );

        verify(refundService, never()).completeCancellation(any(), any());
    }

    @Test
    void cancelCustomerOrder_tossFailureButLookupCanceled_completesCancellation() {
        RefundRequest request = request();
        CancellationResult cancellation = new CancellationResult(
                PaymentGatewayStatus.CANCELED,
                "lookup-transaction-key",
                request.requestedAt().plusSeconds(1)
        );
        PaymentLookupResult lookup = new PaymentLookupResult(
                "payment-key",
                "toss-order-id",
                "CARD",
                PaymentGatewayStatus.CANCELED,
                40_000,
                request.requestedAt().minusDays(1),
                cancellation
        );
        when(refundService.prepareCustomerCancellation(3L, 10L, "단순 변심"))
                .thenReturn(request);
        when(tossPaymentClient.cancel("payment-key", "단순 변심", "idempotency-key"))
                .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        when(tossPaymentClient.find("payment-key")).thenReturn(Optional.of(lookup));

        refundFacade.cancelCustomerOrder(3L, 10L, "단순 변심");

        verify(refundService).completeCancellation(request, cancellation);
    }

    @Test
    void cancelAdminOrder_tossSuccess_completesWithAdminRequest() {
        RefundRequest request = request("ADMIN", "매장 사정");
        CancellationResult result = new CancellationResult(
                PaymentGatewayStatus.CANCELED, "transaction-key", request.requestedAt().plusSeconds(1));
        when(refundService.prepareAdminCancellation(7L, 10L, "매장 사정"))
                .thenReturn(request);
        when(tossPaymentClient.cancel("payment-key", "매장 사정", "idempotency-key"))
                .thenReturn(result);

        refundFacade.cancelAdminOrder(7L, 10L, "매장 사정");

        verify(refundService).completeCancellation(request, result);
    }

    @Test
    void rejectCustomOrder_tossSuccess_completesWithDedicatedRequestType() {
        RefundRequest request = request("ADMIN_REJECTION", "제작 일정이 부족합니다.");
        CancellationResult result = new CancellationResult(
                PaymentGatewayStatus.CANCELED, "transaction-key", request.requestedAt().plusSeconds(1));
        when(refundService.rejectCustomZeroAmountOrder(7L, 10L, "제작 일정이 부족합니다."))
                .thenReturn(false);
        when(refundService.prepareAdminRejection(7L, 10L, "제작 일정이 부족합니다."))
                .thenReturn(request);
        when(tossPaymentClient.cancel("payment-key", "제작 일정이 부족합니다.", "idempotency-key"))
                .thenReturn(result);

        refundFacade.rejectCustomOrder(7L, 10L, "제작 일정이 부족합니다.");

        verify(refundService).completeCancellation(request, result);
    }

    @Test
    void recoverPendingCancellations_requestedCancellation_retriesWithOriginalIdempotencyKey() {
        RefundRequest request = request();
        CancellationResult result = new CancellationResult(
                PaymentGatewayStatus.CANCELED, "transaction-key", request.requestedAt().plusSeconds(1));
        when(refundService.getRequestedCancellations(25)).thenReturn(List.of(request));
        when(tossPaymentClient.cancel("payment-key", "단순 변심", "idempotency-key"))
                .thenReturn(result);

        refundFacade.recoverPendingCancellations(25);

        verify(tossPaymentClient).cancel("payment-key", "단순 변심", "idempotency-key");
        verify(refundService).completeCancellation(request, result);
    }

    private RefundRequest request() {
        return request("CUSTOMER", "단순 변심");
    }

    private RefundRequest request(String canceledBy, String reason) {
        return new RefundRequest(
                PaymentProvider.TOSS,
                30L,
                10L,
                "payment-key",
                "idempotency-key",
                reason,
                canceledBy,
                LocalDateTime.of(2026, 8, 2, 15, 0)
        );
    }
}
