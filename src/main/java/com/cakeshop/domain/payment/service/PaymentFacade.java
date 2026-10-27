package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentExecutionOrder;
import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.service.PaymentApprovalResolver.ApprovalResolution;
import com.cakeshop.domain.payment.service.PaymentApprovalResolver.ApprovalState;
import com.cakeshop.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/** PG 결제 confirm의 실행 순서를 조정한다. */
@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final OrderPaymentQueryService orderPaymentQueryService;
    private final PaymentService paymentService;
    private final PaymentCompensationProcessor compensationProcessor;
    private final PaymentApprovalResolver approvalResolver;
    private final Clock clock;

    /** 일반·수제 주문의 PG 결제를 승인하고 내부 상태를 완료한다. */
    public void confirmPayment(long memberId, long orderId, PaymentConfirmForm form) {
        // 1. 내부 DB
        PaymentOrder ownedOrder = orderPaymentQueryService.getMemberPaymentOrder(memberId, orderId);
        if (isAlreadyCompleted(ownedOrder, orderId, form)) {
            return;
        }

        PaymentExecutionOrder order;
        Payment payment;
        try {
            order = orderPaymentQueryService.getMemberPaymentExecutionOrder(memberId, orderId);
            validatePaymentExpiration(order.paymentExpiresAt());
            payment = paymentService.getReadyPayment(orderId);
            validateRequest(order, payment, form);
        } catch (BusinessException exception) {
            // 최초 DONE 확인 뒤 다른 콜백이 완료했으면 같은 결제 요청인지 다시 검증한다.
            if (isAlreadyCompleted(ownedOrder, orderId, form)) {
                return;
            }
            throw exception;
        }

        // PG 승인 영역 진입
        ResolvedApproval resolved = resolvePreparedOrNewApproval(payment, form, orderId);

        completeOrCompensate(ownedOrder, order, resolved, form);
    }

    private boolean isAlreadyCompleted(
            PaymentOrder order,
            long orderId,
            PaymentConfirmForm form
    ) {
        Payment completedPayment = paymentService.findDonePayment(orderId).orElse(null);
        if (completedPayment == null) {
            return false;
        }
        validateCompletedRequest(order, completedPayment, form);
        validateCompletedProviderState(completedPayment, form);
        return true;
    }

    private void completeOrCompensate(
            PaymentOrder ownedOrder,
            PaymentExecutionOrder order,
            ResolvedApproval resolved,
            PaymentConfirmForm form
    ) {
        try {
            // PG 승인 응답을 받은 뒤에도 내부 완료 직전에 만료 경계를 다시 확인한다.
            validatePaymentExpiration(order.paymentExpiresAt());
            paymentService.completePayment(order, resolved.payment(), resolved.approval().approval());
        } catch (RuntimeException exception) {
            Payment concurrentlyCompleted = findConcurrentlyCompleted(order.orderId());
            if (concurrentlyCompleted == null) {
                throw compensationProcessor.compensateApproved(resolved.payment(), form.getPaymentKey());
            }
            validateCompletedRequest(ownedOrder, concurrentlyCompleted, form);
        }
    }

    /** 0원 주문은 PG 승인 요청 없이 서버가 READY 결제를 완료한다. */
    public void completeZeroAmountGeneralPayment(long memberId, long orderId) {
        // 브라우저 재전송은 이미 완료된 0원 결제를 성공으로 간주하되, 회원 소유권은 먼저 확인한다.
        orderPaymentQueryService.getMemberPaymentOrder(memberId, orderId);
        if (isAlreadyCompletedZeroAmountPayment(orderId)) {
            return;
        }
        try {
            PaymentExecutionOrder order = orderPaymentQueryService.getMemberGeneralPaymentOrder(memberId, orderId);
            if (order.amount().signum() != 0) {
                throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
            }
            // 일반 PG 승인 경로와 동일하게 완료 직전에도 결제 가능 시간을 다시 확인한다.
            validatePaymentExpiration(order.paymentExpiresAt());
            Payment payment = paymentService.getReadyPayment(orderId);
            paymentService.completeZeroAmountGeneralPayment(order, payment, LocalDateTime.now(clock));
        } catch (BusinessException exception) {
            // 잠금을 기다리던 중 동일 주문의 0원 결제가 완료됐으면 중복 요청도 성공이다.
            if (!isAlreadyCompletedZeroAmountPayment(orderId)) {
                throw exception;
            }
        }
    }

    private boolean isAlreadyCompletedZeroAmountPayment(long orderId) {
        Payment completedPayment = paymentService.findDonePayment(orderId).orElse(null);
        if (completedPayment == null) {
            return false;
        }
        if (completedPayment.getAmount() == null || completedPayment.getAmount().signum() != 0) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        return true;
    }

    private ResolvedApproval resolvePreparedOrNewApproval(
            Payment payment,
            PaymentConfirmForm form,
            long orderId
    ) {
        return compensationProcessor.findPrepared(payment)
                .map(prepared -> resolvePreparedApproval(payment, prepared, form, orderId))
                .orElseGet(() -> resolveCurrentOrApprove(payment, form));
    }

    private ResolvedApproval resolvePreparedApproval(
            Payment payment,
            PaymentRecoveryService.CompensationRequest prepared,
            PaymentConfirmForm form,
            long orderId
    ) {
        ApprovalResolution recoveryState = approvalResolver.resolveCompensationState(prepared);
        return switch (recoveryState.state()) {
            case NOT_APPROVED -> {
                compensationProcessor.releaseUnapproved(prepared);
                yield resolveCurrentOrApprove(paymentService.getReadyPayment(orderId), form);
            }
            case IN_PROGRESS -> throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
            case DONE -> resolvePreparedDonePayment(payment, prepared, form);
            case CANCELED -> throw compensationProcessor.reconcileCanceled(payment, recoveryState.lookup());
            case FINAL_OR_UNKNOWN -> throw compensationProcessor.cancelPrepared(prepared);
        };
    }

    private ResolvedApproval resolvePreparedDonePayment(
            Payment payment,
            PaymentRecoveryService.CompensationRequest prepared,
            PaymentConfirmForm form
    ) {
        if (!prepared.paymentKey().equals(form.getPaymentKey())) {
            throw compensationProcessor.cancelPrepared(prepared);
        }
        return new ResolvedApproval(
                payment,
                requireApproved(approvalResolver.resolveCurrent(payment, form))
        );
    }

    private ResolvedApproval resolveCurrentOrApprove(Payment payment, PaymentConfirmForm form) {
        ApprovalResolution current = approvalResolver.resolveCurrent(payment, form);
        return switch (current.state()) {
            case DONE -> new ResolvedApproval(payment, requireApproved(current));
            case CANCELED -> throw compensationProcessor.reconcileCanceled(payment, current.lookup());
            case NOT_APPROVED, IN_PROGRESS, FINAL_OR_UNKNOWN -> approvePayment(payment, form);
        };
    }

    private ResolvedApproval approvePayment(Payment payment, PaymentConfirmForm form) {
        compensationProcessor.prepareBeforeApproval(payment, form.getPaymentKey());
        ApprovalResolution approved = approvalResolver.approve(payment, form);
        return switch (approved.state()) {
            case DONE -> new ResolvedApproval(payment, requireApproved(approved));
            case CANCELED -> throw compensationProcessor.reconcileCanceled(payment, approved.lookup());
            case NOT_APPROVED, IN_PROGRESS, FINAL_OR_UNKNOWN ->
                    throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        };
    }

    private ApprovalResolution requireApproved(ApprovalResolution resolution) {
        if (resolution.state() != ApprovalState.DONE || resolution.approval() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
        }
        return resolution;
    }

    private Payment findConcurrentlyCompleted(long orderId) {
        try {
            return paymentService.findDonePayment(orderId).orElse(null);
        } catch (RuntimeException lookupFailure) {
            return null;
        }
    }

    private void validateCompletedRequest(PaymentOrder order, Payment payment, PaymentConfirmForm form) {
        if (form == null
                || payment.getPaymentKey() == null
                || !payment.getPaymentKey().equals(form.getPaymentKey())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        }
        if (!payment.getGatewayOrderId().equals(form.getGatewayOrderId())
                || !order.orderNumber().equals(form.getGatewayOrderId())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_ID_MISMATCH);
        }
        if (!sameAmount(order.finalAmount(), form.getAmount())
                || !sameAmount(payment.getAmount(), form.getAmount())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
    }

    private void validateCompletedProviderState(Payment payment, PaymentConfirmForm form) {
        ApprovalResolution resolution = approvalResolver.resolveCurrent(payment, form);
        if (resolution.state() == ApprovalState.CANCELED) {
            throw compensationProcessor.reconcileCanceled(payment, resolution.lookup());
        }
        requireApproved(resolution);
    }

    private void validatePaymentExpiration(LocalDateTime paymentExpiresAt) {
        if (paymentExpiresAt == null || !LocalDateTime.now(clock).isBefore(paymentExpiresAt)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_EXPIRED);
        }
    }

    private void validateRequest(PaymentExecutionOrder order, Payment payment, PaymentConfirmForm form) {
        if (form == null || form.getPaymentKey() == null || form.getPaymentKey().isBlank()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        }
        if (!payment.getGatewayOrderId().equals(form.getGatewayOrderId())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_ID_MISMATCH);
        }
        if (!sameAmount(order.amount(), form.getAmount())
                || !sameAmount(payment.getAmount(), form.getAmount())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
    }

    private boolean sameAmount(BigDecimal expected, BigDecimal actual) {
        return expected != null && actual != null && expected.compareTo(actual) == 0;
    }

    private record ResolvedApproval(Payment payment, ApprovalResolution approval) {
    }
}
