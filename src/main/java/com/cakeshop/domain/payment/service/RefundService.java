package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberOrderQueryService;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.service.payment.OrderPaymentCancellationCommandService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전체 결제 취소 요청과 주문 취소·수제 반려 완료를 담당한다. */
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentMapper paymentMapper;
    private final OrderPaymentCancellationCommandService orderPaymentCancellationCommandService;
    private final MemberService memberService;
    private final MemberOrderQueryService memberOrderQueryService;
    private final Clock clock;

    /** 고객 요청은 일반 주문 또는 승인 전 수제 주문만 취소할 수 있다. */
    @Transactional
    public RefundRequest prepareCustomerCancellation(long memberId, long orderId, String reason) {
        validateCancellationInput(memberId, reason);
        validateActiveMember(memberId);
        orderPaymentCancellationCommandService.lockCustomerOrderForPaymentCancellation(memberId, orderId);
        return prepareCancellation(
                memberId,
                orderId,
                reason,
                OrderPaymentCancellationCommandService.CUSTOMER
        );
    }

    /** 일반 주문에 대한 관리자 취소 요청을 저장한다. */
    @Transactional
    public RefundRequest prepareAdminCancellation(long adminMemberId, long orderId, String reason) {
        validateCancellationInput(adminMemberId, reason);
        requireActiveAdmin(adminMemberId);
        orderPaymentCancellationCommandService.lockOrderForPaymentCancellation(orderId);
        return prepareCancellation(
                adminMemberId,
                orderId,
                reason,
                OrderPaymentCancellationCommandService.ADMIN
        );
    }

    /** 승인 전 수제 주문의 관리자 반려·환불 요청을 저장한다. */
    @Transactional
    public RefundRequest prepareAdminRejection(long adminMemberId, long orderId, String reason) {
        validateCancellationInput(adminMemberId, reason);
        requireActiveAdmin(adminMemberId);
        orderPaymentCancellationCommandService.lockOrderForPaymentCancellation(orderId);
        return prepareCancellation(
                adminMemberId,
                orderId,
                reason,
                OrderPaymentCancellationCommandService.ADMIN_REJECTION
        );
    }

    /** 0원 고객 주문을 PG 호출 없이 요청 유형에 맞는 최종 상태로 완료한다. */
    @Transactional
    public boolean cancelCustomerZeroAmountOrder(long memberId, long orderId, String reason) {
        validateCancellationInput(memberId, reason);
        validateActiveMember(memberId);
        orderPaymentCancellationCommandService.lockCustomerOrderForPaymentCancellation(memberId, orderId);
        return cancelZeroAmountOrder(
                memberId,
                orderId,
                reason,
                OrderPaymentCancellationCommandService.CUSTOMER
        );
    }

    /** 0원 일반 주문의 관리자 취소를 PG 호출 없이 완료한다. */
    @Transactional
    public boolean cancelAdminZeroAmountOrder(long adminMemberId, long orderId, String reason) {
        validateCancellationInput(adminMemberId, reason);
        requireActiveAdmin(adminMemberId);
        orderPaymentCancellationCommandService.lockOrderForPaymentCancellation(orderId);
        return cancelZeroAmountOrder(
                adminMemberId,
                orderId,
                reason,
                OrderPaymentCancellationCommandService.ADMIN
        );
    }

    /** 0원 수제 주문의 관리자 반려를 PG 호출 없이 완료한다. */
    @Transactional
    public boolean rejectCustomZeroAmountOrder(long adminMemberId, long orderId, String reason) {
        validateCancellationInput(adminMemberId, reason);
        requireActiveAdmin(adminMemberId);
        orderPaymentCancellationCommandService.lockOrderForPaymentCancellation(orderId);
        return cancelZeroAmountOrder(
                adminMemberId,
                orderId,
                reason,
                OrderPaymentCancellationCommandService.ADMIN_REJECTION
        );
    }

    private RefundRequest prepareCancellation(
            long requestedBy,
            long orderId,
            String reason,
            String requestType
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        Payment payment = paymentMapper.findDonePaymentByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE));
        if (payment.getPaymentKey() == null || payment.getPaymentKey().isBlank()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE);
        }

        PaymentCancellation requestedCancellation = paymentMapper
                .findRequestedCancellationByPaymentId(payment.getId())
                .orElse(null);
        if (requestedCancellation != null) {
            return reuseRequestedCancellation(requestedCancellation, payment, orderId, requestType);
        }

        requirePaymentCancellationAvailable(orderId, requestType, now);
        PaymentCancellation cancellation = new PaymentCancellation();
        cancellation.setPaymentId(payment.getId());
        cancellation.setIdempotencyKey("CANCEL-" + UUID.randomUUID());
        cancellation.setCancelAmount(payment.getAmount());
        cancellation.setCancelReason(reason.trim());
        cancellation.setRequestType(requestType);
        cancellation.setRequestedBy(requestedBy);
        if (paymentMapper.insertPaymentCancellation(cancellation) != 1) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE);
        }

        return new RefundRequest(
                cancellation.getId(),
                orderId,
                payment.getPaymentKey(),
                cancellation.getIdempotencyKey(),
                cancellation.getCancelReason(),
                requestType,
                now
        );
    }

    private boolean cancelZeroAmountOrder(
            long requestedBy,
            long orderId,
            String reason,
            String requestType
    ) {
        Payment payment = paymentMapper.findDonePaymentByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE));
        if (payment.getAmount() == null || payment.getAmount().signum() != 0) {
            return false;
        }

        LocalDateTime canceledAt = LocalDateTime.now(clock);
        requirePaymentCancellationAvailable(orderId, requestType, canceledAt);
        requireOneRow(paymentMapper.cancelIfDone(payment.getId(), canceledAt));
        completeOrderCancellation(orderId, requestType, requestedBy, reason.trim(), canceledAt, canceledAt);
        return true;
    }

    private RefundRequest reuseRequestedCancellation(
            PaymentCancellation cancellation,
            Payment payment,
            long orderId,
            String requestType
    ) {
        if (cancellation.getStatus() != PaymentCancellationStatus.REQUESTED
                || !Long.valueOf(payment.getId()).equals(cancellation.getPaymentId())
                || cancellation.getId() == null
                || cancellation.getIdempotencyKey() == null
                || cancellation.getIdempotencyKey().isBlank()
                || cancellation.getCancelReason() == null
                || cancellation.getCancelReason().isBlank()
                || !Objects.equals(requestType, cancellation.getRequestType())
                || cancellation.getRequestedBy() == null
                || cancellation.getRequestedAt() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE);
        }
        requirePaymentCancellationAvailable(orderId, requestType, cancellation.getRequestedAt());
        return new RefundRequest(
                cancellation.getId(),
                orderId,
                payment.getPaymentKey(),
                cancellation.getIdempotencyKey(),
                cancellation.getCancelReason(),
                requestType,
                cancellation.getRequestedAt()
        );
    }

    /** PG 취소 성공 뒤 결제와 요청 유형에 맞는 주문 최종 상태를 한 트랜잭션으로 확정한다. */
    @Transactional
    public void completeCancellation(RefundRequest request, CancellationResult result) {
        if (request == null
                || result == null
                || result.status() != PaymentGatewayStatus.CANCELED
                || result.transactionKey() == null
                || result.transactionKey().isBlank()
                || result.canceledAt() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED);
        }
        // 준비와 완료 모두 주문 → 취소 요청 순서로 잠가 서로의 잠금을 역순으로 기다리지 않는다.
        orderPaymentCancellationCommandService.lockOrderForPaymentCancellation(request.orderId());
        PaymentCancellation cancellation = paymentMapper
                .findPaymentCancellationByIdForUpdate(request.cancellationId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED));
        Payment payment = paymentMapper.findPaymentById(cancellation.getPaymentId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED));
        if (!Long.valueOf(request.orderId()).equals(payment.getOrderId())
                || !Objects.equals(request.paymentKey(), payment.getPaymentKey())
                || !Objects.equals(request.idempotencyKey(), cancellation.getIdempotencyKey())
                || !Objects.equals(request.reason(), cancellation.getCancelReason())
                || !Objects.equals(request.canceledBy(), cancellation.getRequestType())
                || cancellation.getRequestedBy() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED);
        }

        if (cancellation.getStatus() == PaymentCancellationStatus.DONE) {
            if (payment.getStatus() != PaymentStatus.CANCELED
                    || !orderPaymentCancellationCommandService.isPaymentCancellationCompleted(
                            request.orderId(), request.canceledBy()
                    )
                    || !Objects.equals(cancellation.getTransactionKey(), result.transactionKey())) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED);
            }
            completeOrderCancellation(
                    request.orderId(),
                    request.canceledBy(),
                    cancellation.getRequestedBy(),
                    cancellation.getCancelReason(),
                    request.requestedAt(),
                    result.canceledAt()
            );
            return;
        }
        if (cancellation.getStatus() != PaymentCancellationStatus.REQUESTED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED);
        }
        LocalDateTime canceledAt = result.canceledAt();
        requirePositive(paymentMapper.completeCancellationIfRequested(
                cancellation.getId(),
                result.transactionKey(),
                canceledAt
        ));
        completeOrderCancellation(
                request.orderId(),
                request.canceledBy(),
                cancellation.getRequestedBy(),
                cancellation.getCancelReason(),
                request.requestedAt(),
                canceledAt
        );
    }

    /** PG 오류로 남은 고객·관리자 취소·반려 요청을 같은 멱등키로 재처리한다. */
    @Transactional(readOnly = true)
    public List<RefundRequest> getRequestedCancellations(int limit) {
        if (limit <= 0) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        return paymentMapper.findRequestedRefundCancellations(limit).stream()
                .map(this::toRefundRequest)
                .toList();
    }

    private RefundRequest toRefundRequest(PaymentCancellation cancellation) {
        Payment payment = paymentMapper.findPaymentById(cancellation.getPaymentId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING));
        return reuseRequestedCancellation(
                cancellation,
                payment,
                payment.getOrderId(),
                cancellation.getRequestType()
        );
    }

    private void validateCancellationInput(long requestedBy, String reason) {
        if (requestedBy <= 0 || reason == null || reason.isBlank() || reason.length() > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validateActiveMember(long memberId) {
        if (!memberService.isActiveMember(memberId)) {
            throw new BusinessException(OrderErrorCode.MEMBER_NOT_AVAILABLE);
        }
    }

    private void requireActiveAdmin(long memberId) {
        if (!memberOrderQueryService.isActiveAdmin(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private void requirePaymentCancellationAvailable(
            long orderId,
            String requestType,
            LocalDateTime requestedAt
    ) {
        if (!orderPaymentCancellationCommandService.isPaymentCancellationAvailable(
                orderId,
                requestType,
                requestedAt
        )) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE);
        }
    }

    private void completeOrderCancellation(
            long orderId,
            String requestType,
            long requestedBy,
            String reason,
            LocalDateTime requestedAt,
            LocalDateTime canceledAt
    ) {
        if (!orderPaymentCancellationCommandService.completePaymentCancellation(
                orderId,
                requestType,
                requestedBy,
                reason,
                requestedAt,
                canceledAt
        )) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED);
        }
    }

    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED);
        }
    }

    private void requirePositive(int affectedRows) {
        if (affectedRows <= 0) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_COMPLETE_FAILED);
        }
    }

    public record RefundRequest(
            long cancellationId,
            long orderId,
            String paymentKey,
            String idempotencyKey,
            String reason,
            String canceledBy,
            LocalDateTime requestedAt
    ) {
    }
}
