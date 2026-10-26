package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentExecutionOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentProduct;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.TossPaymentClient;
import com.cakeshop.domain.payment.infra.TossPaymentGateway;
import com.cakeshop.domain.payment.infra.ApprovalResult;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import com.cakeshop.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTests {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 8, 1, 10, 0);
    private static final Clock CLOCK = Clock.fixed(
            NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private OrderPaymentQueryService orderPaymentQueryService;

    private PaymentOrder ownedOrder;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentRecoveryService paymentRecoveryService;

    @Mock
    private TossPaymentClient tossPaymentClient;

    private PaymentFacade paymentFacade;

    @BeforeEach
    void setUp() {
        paymentFacade = new PaymentFacade(
                orderPaymentQueryService,
                paymentService,
                new PaymentCompensationProcessor(
                        paymentRecoveryService,
                        new TossPaymentGateway(tossPaymentClient, null),
                        new TossPaymentApprovalResolver(new TossPaymentGateway(tossPaymentClient, null))
                ),
                new TossPaymentApprovalResolver(new TossPaymentGateway(tossPaymentClient, null)),
                CLOCK
        );
        ownedOrder = new PaymentOrder(
                1L,
                "ORD-100",
                true,
                true,
                BigDecimal.valueOf(30_000),
                BigDecimal.ZERO,
                BigDecimal.valueOf(30_000),
                NOW.plusDays(1),
                NOW.plusMinutes(5),
                "홍길동",
                "010-1111-2222",
                List.of()
        );
        lenient().when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L))
                .thenReturn(ownedOrder);
    }

    @Test
    void confirmPayment_validRequest_approvesAndCompletesPayment() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        ApprovalResult approval = approval();

        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L))
                .thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenReturn(approval);

        paymentFacade.confirmPayment(10L, 1L, form);

        verify(paymentService).completePayment(
                order,
                payment,
                approval
        );
    }

    @Test
    void confirmPayment_customOrder_approvesAndCompletesThroughCustomPath() {
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                1L,
                OrderType.CUSTOM,
                BigDecimal.valueOf(30_000),
                NOW.plusMinutes(5),
                List.of(new PaymentProduct(20L, 30L, 1))
        );
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        ApprovalResult approval = approval();

        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L))
                .thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenReturn(approval);

        paymentFacade.confirmPayment(10L, 1L, form);

        verify(paymentService).completePayment(order, payment, approval);
    }

    @Test
    void completeZeroAmountGeneralPayment_expiredOrder_rejectsBeforeCompleting() {
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                1L, BigDecimal.ZERO, NOW, List.of()
        );
        when(orderPaymentQueryService.getMemberGeneralPaymentOrder(10L, 1L)).thenReturn(order);

        assertPaymentError(
                () -> paymentFacade.completeZeroAmountGeneralPayment(10L, 1L),
                PaymentErrorCode.PAYMENT_EXPIRED
        );

        verify(paymentService, never()).getReadyPayment(1L);
    }

    @Test
    void completeZeroAmountGeneralPayment_completedZeroPayment_returnsSuccessfully() {
        Payment completedPayment = payment();
        completedPayment.setAmount(BigDecimal.ZERO);
        completedPayment.setStatus(PaymentStatus.DONE);
        when(paymentService.findDonePayment(1L)).thenReturn(Optional.of(completedPayment));

        paymentFacade.completeZeroAmountGeneralPayment(10L, 1L);

        verify(orderPaymentQueryService, never()).getMemberGeneralPaymentOrder(10L, 1L);
        verify(paymentService, never()).getReadyPayment(1L);
    }

    @Test
    void confirmPayment_amountMismatch_doesNotCallToss() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(29_000));

        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L))
                .thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(
                        10L,
                        1L,
                        form
                ),
                PaymentErrorCode.AMOUNT_MISMATCH
        );

        verify(tossPaymentClient, never()).approve(
                "payment-key",
                "ORD-100",
                29_000L,
                "PAY-1"
        );
        verify(paymentService, never()).completePayment(
                order,
                payment,
                approval()
        );
    }

    @Test
    void confirmPayment_expiredOrder_doesNotLoadPayment() {
        PaymentExecutionOrder order = order(NOW);

        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L))
                .thenReturn(order);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(
                        10L,
                        1L,
                        form(BigDecimal.valueOf(30_000))
                ),
                PaymentErrorCode.PAYMENT_EXPIRED
        );

        verify(paymentService, never()).getReadyPayment(1L);
        verify(tossPaymentClient, never()).approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        );
    }

    @Test
    void confirmPayment_tossOrderIdMismatch_doesNotCallToss() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        form.setTossOrderId("ORD-OTHER");

        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L))
                .thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(
                        10L,
                        1L,
                        form
                ),
                PaymentErrorCode.TOSS_ORDER_ID_MISMATCH
        );

        verify(tossPaymentClient, never()).approve(
                "payment-key",
                "ORD-OTHER",
                30_000L,
                "PAY-1"
        );
    }

    @Test
    void confirmPayment_approvalPaymentKeyMismatch_doesNotCompletePayment() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        ApprovalResult mismatchedApproval = new ApprovalResult(
                "other-payment-key",
                "ORD-100",
                "카드",
                PaymentGatewayStatus.DONE,
                30_000L,
                NOW.plusSeconds(10)
        );

        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L))
                .thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenReturn(mismatchedApproval);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(
                        10L,
                        1L,
                        form
                ),
                PaymentErrorCode.TOSS_APPROVAL_FAILED
        );

        verify(paymentService, never()).completePayment(
                order,
                payment,
                mismatchedApproval
        );
    }

    @Test
    void confirmPayment_sameCompletedCallback_returnsWithoutTossRetry() {
        Payment completedPayment = payment();
        completedPayment.setStatus(PaymentStatus.DONE);
        completedPayment.setPaymentKey("payment-key");
        PaymentLookupResult lookup = new PaymentLookupResult(
                "payment-key",
                "ORD-100",
                "카드",
                PaymentGatewayStatus.DONE,
                30_000L,
                NOW.plusSeconds(10),
                null
        );
        when(paymentService.findDonePayment(1L))
                .thenReturn(Optional.of(completedPayment));
        when(tossPaymentClient.find("payment-key"))
                .thenReturn(Optional.of(lookup));

        paymentFacade.confirmPayment(
                10L,
                1L,
                form(BigDecimal.valueOf(30_000))
        );

        verify(orderPaymentQueryService, never()).getMemberPaymentExecutionOrder(10L, 1L);
        verify(tossPaymentClient, never()).approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        );
    }

    @Test
    void confirmPayment_localDoneButProviderCanceled_completesCompensation() {
        Payment completedPayment = payment();
        completedPayment.setStatus(PaymentStatus.DONE);
        completedPayment.setPaymentKey("payment-key");
        CompensationRequest request = compensationRequest();
        CancellationResult cancellation = cancellation();
        PaymentLookupResult lookup = new PaymentLookupResult(
                "payment-key",
                "ORD-100",
                "카드",
                PaymentGatewayStatus.CANCELED,
                30_000L,
                NOW.plusSeconds(10),
                cancellation
        );
        when(paymentService.findDonePayment(1L))
                .thenReturn(Optional.of(completedPayment));
        when(tossPaymentClient.find("payment-key"))
                .thenReturn(Optional.of(lookup));
        when(paymentRecoveryService.createRequest(completedPayment, "payment-key"))
                .thenReturn(request);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(
                        10L,
                        1L,
                        form(BigDecimal.valueOf(30_000))
                ),
                PaymentErrorCode.PAYMENT_COMPENSATED
        );

        verify(paymentRecoveryService).prepareCompensation(request);
        verify(paymentRecoveryService).completeCompensation(request, cancellation);
    }

    @Test
    void confirmPayment_approvalTimeout_lookupDone_completesPayment() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        PaymentLookupResult lookup = new PaymentLookupResult(
                "payment-key",
                "ORD-100",
                "카드",
                PaymentGatewayStatus.DONE,
                30_000L,
                NOW.plusSeconds(10),
                null
        );
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.find("payment-key"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(lookup));
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenThrow(new BusinessException(PaymentErrorCode.TOSS_APPROVAL_FAILED));

        paymentFacade.confirmPayment(10L, 1L, form);

        verify(paymentService).completePayment(
                order,
                payment,
                lookup.toApprovalResult()
        );
    }

    @Test
    void confirmPayment_approvalAndLookupFailure_persistsRecoveryTarget() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        CompensationRequest request = compensationRequest();
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.find("payment-key"))
                .thenReturn(Optional.empty())
                .thenThrow(new BusinessException(
                        PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED
                ));
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenThrow(new BusinessException(PaymentErrorCode.TOSS_APPROVAL_FAILED));
        when(paymentRecoveryService.createRequest(payment, "payment-key"))
                .thenReturn(request);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(10L, 1L, form),
                PaymentErrorCode.PAYMENT_RECOVERY_PENDING
        );

        verify(paymentRecoveryService).prepareCompensation(request);
        verify(paymentService, never()).completePayment(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void confirmPayment_approvalFailureLookupEmpty_persistsRecoveryTarget() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        CompensationRequest request = compensationRequest();
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.find("payment-key")).thenReturn(Optional.empty());
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenThrow(new BusinessException(PaymentErrorCode.TOSS_APPROVAL_FAILED));
        when(paymentRecoveryService.createRequest(payment, "payment-key"))
                .thenReturn(request);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(
                        10L,
                        1L,
                        form(BigDecimal.valueOf(30_000))
                ),
                PaymentErrorCode.PAYMENT_RECOVERY_PENDING
        );

        verify(paymentRecoveryService).prepareCompensation(request);
    }

    @Test
    void confirmPayment_lookupInProgress_usesSameIdempotentApproval() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        ApprovalResult approval = approval();
        PaymentLookupResult inProgress = new PaymentLookupResult(
                "payment-key",
                "ORD-100",
                null,
                PaymentGatewayStatus.IN_PROGRESS,
                30_000L,
                null,
                null
        );
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.find("payment-key"))
                .thenReturn(Optional.of(inProgress));
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenReturn(approval);

        paymentFacade.confirmPayment(
                10L,
                1L,
                form(BigDecimal.valueOf(30_000))
        );

        verify(paymentService).completePayment(order, payment, approval);
    }

    @Test
    void confirmPayment_internalFailure_cancelsApprovedPayment() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        ApprovalResult approval = approval();
        CompensationRequest request = new CompensationRequest(
                20L,
                1L,
                "payment-key",
                "COMPENSATE-20",
                BigDecimal.valueOf(30_000),
                "내부 주문 처리 실패로 인한 자동 결제 취소"
        );
        CancellationResult cancellation = new CancellationResult(
                PaymentGatewayStatus.CANCELED,
                "cancel-transaction",
                NOW.plusSeconds(20)
        );
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenReturn(approval);
        org.mockito.Mockito.doThrow(
                new BusinessException(PaymentErrorCode.PAYMENT_COMPLETE_FAILED)
        ).when(paymentService).completePayment(order, payment, approval);
        when(paymentRecoveryService.createRequest(payment, "payment-key"))
                .thenReturn(request);
        when(tossPaymentClient.cancel(
                "payment-key",
                request.reason(),
                "COMPENSATE-20"
        )).thenReturn(cancellation);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(10L, 1L, form),
                PaymentErrorCode.PAYMENT_COMPENSATED
        );

        verify(paymentRecoveryService, times(2)).prepareCompensation(request);
        verify(paymentRecoveryService).completeCompensation(request, cancellation);
    }

    @Test
    void confirmPayment_expiredDuringApproval_compensatesWithoutInternalCompletion() {
        MutableClock clock = new MutableClock(NOW, ZoneId.of("Asia/Seoul"));
        paymentFacade = new PaymentFacade(
                orderPaymentQueryService,
                paymentService,
                new PaymentCompensationProcessor(
                        paymentRecoveryService,
                        new TossPaymentGateway(tossPaymentClient, null),
                        new TossPaymentApprovalResolver(new TossPaymentGateway(tossPaymentClient, null))
                ),
                new TossPaymentApprovalResolver(new TossPaymentGateway(tossPaymentClient, null)),
                clock
        );
        PaymentExecutionOrder order = order(NOW.plusSeconds(1));
        Payment payment = payment();
        PaymentConfirmForm form = form(BigDecimal.valueOf(30_000));
        ApprovalResult approval = approval();
        CompensationRequest request = compensationRequest();
        CancellationResult cancellation = cancellation();

        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenAnswer(invocation -> {
            clock.setInstant(NOW.plusSeconds(1));
            return approval;
        });
        when(paymentRecoveryService.createRequest(payment, "payment-key")).thenReturn(request);
        when(tossPaymentClient.cancel(
                "payment-key",
                request.reason(),
                request.idempotencyKey()
        )).thenReturn(cancellation);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(10L, 1L, form),
                PaymentErrorCode.PAYMENT_COMPENSATED
        );

        verify(paymentService, never()).completePayment(order, payment, approval);
        verify(paymentRecoveryService, times(2)).prepareCompensation(request);
        verify(paymentRecoveryService).completeCompensation(request, cancellation);
    }

    @Test
    void confirmPayment_compensationPrepareFailure_doesNotCancelWithoutRecoveryRecord() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        ApprovalResult approval = approval();
        CompensationRequest request = compensationRequest();
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(paymentRecoveryService.createRequest(payment, "payment-key")).thenReturn(request);
        org.mockito.Mockito.doThrow(new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING))
                .when(paymentRecoveryService).prepareCompensation(request);

        assertPaymentError(
                () -> paymentFacade.confirmPayment(10L, 1L, form(BigDecimal.valueOf(30_000))),
                PaymentErrorCode.PAYMENT_RECOVERY_PENDING
        );

        verify(tossPaymentClient, never()).cancel(
                "payment-key",
                request.reason(),
                request.idempotencyKey()
        );
    }

    @Test
    void confirmPayment_concurrentCompletion_doesNotCancelPayment() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment readyPayment = payment();
        Payment donePayment = payment();
        donePayment.setStatus(PaymentStatus.DONE);
        donePayment.setPaymentKey("payment-key");
        ApprovalResult approval = approval();
        when(paymentService.findDonePayment(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(donePayment));
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(readyPayment);
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenReturn(approval);
        org.mockito.Mockito.doThrow(
                new BusinessException(PaymentErrorCode.PAYMENT_COMPLETE_FAILED)
        ).when(paymentService).completePayment(order, readyPayment, approval);

        paymentFacade.confirmPayment(
                10L,
                1L,
                form(BigDecimal.valueOf(30_000))
        );

        verify(tossPaymentClient, never()).cancel(
                "payment-key",
                "내부 주문 처리 실패로 인한 자동 결제 취소",
                "COMPENSATE-20"
        );
    }

    @Test
    void confirmPayment_cancelTimeout_lookupCanceled_completesRecovery() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        ApprovalResult approval = approval();
        CompensationRequest request = compensationRequest();
        CancellationResult cancellation = cancellation();
        PaymentLookupResult canceledLookup = new PaymentLookupResult(
                "payment-key",
                "ORD-100",
                "카드",
                PaymentGatewayStatus.CANCELED,
                30_000L,
                NOW.plusSeconds(10),
                cancellation
        );
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(tossPaymentClient.find("payment-key"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(canceledLookup));
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        )).thenReturn(approval);
        org.mockito.Mockito.doThrow(
                new BusinessException(PaymentErrorCode.PAYMENT_COMPLETE_FAILED)
        ).when(paymentService).completePayment(order, payment, approval);
        when(paymentRecoveryService.createRequest(payment, "payment-key"))
                .thenReturn(request);
        when(tossPaymentClient.cancel(
                request.paymentKey(),
                request.reason(),
                request.idempotencyKey()
        )).thenThrow(new BusinessException(PaymentErrorCode.TOSS_CANCEL_FAILED));

        assertPaymentError(
                () -> paymentFacade.confirmPayment(
                        10L,
                        1L,
                        form(BigDecimal.valueOf(30_000))
                ),
                PaymentErrorCode.PAYMENT_COMPENSATED
        );

        verify(paymentRecoveryService).completeCompensation(request, cancellation);
    }

    @Test
    void confirmPayment_duplicateCallbackDuringCompletion_resumesInternalCompletion() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        CompensationRequest request = compensationRequest();
        ApprovalResult approval = new ApprovalResult(
                request.paymentKey(),
                "ORD-100",
                "카드",
                PaymentGatewayStatus.DONE,
                30_000L,
                NOW
        );
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(paymentRecoveryService.findPreparedCompensation(payment))
                .thenReturn(Optional.of(request));
        when(tossPaymentClient.find(request.paymentKey())).thenReturn(Optional.of(
                new PaymentLookupResult(
                        request.paymentKey(),
                        "ORD-100",
                        "카드",
                        PaymentGatewayStatus.DONE,
                        30_000L,
                        NOW,
                        null
                )
        ));

        paymentFacade.confirmPayment(
                10L,
                1L,
                form(BigDecimal.valueOf(30_000))
        );

        verify(tossPaymentClient, never()).approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        );
        verify(tossPaymentClient, never()).cancel(
                request.paymentKey(),
                request.reason(),
                request.idempotencyKey()
        );
        verify(paymentService).completePayment(order, payment, approval);
    }

    @Test
    void confirmPayment_unapprovedPreparedCompensation_releasesAndApprovesNewPayment() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        payment.setPaymentKey("stale-payment-key");
        Payment refreshedPayment = payment();
        refreshedPayment.setIdempotencyKey("PAY-RETRY");
        CompensationRequest request = new CompensationRequest(
                20L,
                1L,
                "stale-payment-key",
                "COMPENSATE-20",
                BigDecimal.valueOf(30_000),
                "자동 취소"
        );
        ApprovalResult approval = approval();
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment, refreshedPayment);
        when(paymentRecoveryService.findPreparedCompensation(payment))
                .thenReturn(Optional.of(request));
        when(tossPaymentClient.find("stale-payment-key")).thenReturn(Optional.empty());
        when(tossPaymentClient.find("payment-key")).thenReturn(Optional.empty());
        when(tossPaymentClient.approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-RETRY"
        )).thenReturn(approval);

        paymentFacade.confirmPayment(10L, 1L, form(BigDecimal.valueOf(30_000)));

        verify(paymentRecoveryService).releaseUnapprovedCompensation(request);
        verify(paymentService).completePayment(order, refreshedPayment, approval);
    }

    @Test
    void confirmPayment_inProgressPreparedCompensation_keepsRecoveryRequest() {
        PaymentExecutionOrder order = order(NOW.plusMinutes(5));
        Payment payment = payment();
        payment.setPaymentKey("stale-payment-key");
        CompensationRequest request = new CompensationRequest(
                20L,
                1L,
                "stale-payment-key",
                "COMPENSATE-20",
                BigDecimal.valueOf(30_000),
                "자동 취소"
        );
        when(orderPaymentQueryService.getMemberPaymentExecutionOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);
        when(paymentRecoveryService.findPreparedCompensation(payment))
                .thenReturn(Optional.of(request));
        when(tossPaymentClient.find("stale-payment-key")).thenReturn(Optional.of(
                new PaymentLookupResult(
                        "stale-payment-key",
                        "ORD-100",
                        "카드",
                        PaymentGatewayStatus.IN_PROGRESS,
                        30_000L,
                        null,
                        null
                )
        ));
        assertThatThrownBy(() -> paymentFacade.confirmPayment(
                10L,
                1L,
                form(BigDecimal.valueOf(30_000))
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_RECOVERY_PENDING)
        );

        verify(paymentRecoveryService, never()).releaseUnapprovedCompensation(request);
        verify(tossPaymentClient, never()).approve(
                "payment-key",
                "ORD-100",
                30_000L,
                "PAY-1"
        );
    }

    private PaymentExecutionOrder order(LocalDateTime expiresAt) {
        return new PaymentExecutionOrder(
                1L,
                BigDecimal.valueOf(30_000),
                expiresAt,
                List.of(new PaymentProduct(200L, 100L, 2))
        );
    }

    private Payment payment() {
        Payment payment = new Payment();
        payment.setId(20L);
        payment.setOrderId(1L);
        payment.setTossOrderId("ORD-100");
        payment.setIdempotencyKey("PAY-1");
        payment.setAmount(BigDecimal.valueOf(30_000));
        payment.setStatus(PaymentStatus.READY);
        return payment;
    }

    private PaymentConfirmForm form(BigDecimal amount) {
        PaymentConfirmForm form = new PaymentConfirmForm();
        form.setPaymentKey("payment-key");
        form.setTossOrderId("ORD-100");
        form.setAmount(amount);
        return form;
    }

    private ApprovalResult approval() {
        return new ApprovalResult(
                "payment-key",
                "ORD-100",
                "카드",
                PaymentGatewayStatus.DONE,
                30_000L,
                NOW.plusSeconds(10)
        );
    }

    private CompensationRequest compensationRequest() {
        return new CompensationRequest(
                20L,
                1L,
                "payment-key",
                "COMPENSATE-20",
                BigDecimal.valueOf(30_000),
                "내부 주문 처리 실패로 인한 자동 결제 취소"
        );
    }

    private CancellationResult cancellation() {
        return new CancellationResult(
                PaymentGatewayStatus.CANCELED,
                "cancel-transaction",
                NOW.plusSeconds(20)
        );
    }

    private void assertPaymentError(
            Runnable action,
            PaymentErrorCode expected
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expected)
                );
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(LocalDateTime dateTime, ZoneId zone) {
            this(dateTime.atZone(zone).toInstant(), zone);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void setInstant(LocalDateTime dateTime) {
            instant = dateTime.atZone(zone).toInstant();
        }
    }
}
