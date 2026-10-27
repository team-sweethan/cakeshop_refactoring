package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentRecoveryService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PG 승인 후 내부 완료 실패를 전액 취소하고 로컬 상태와 재고를 복구한다. */
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private static final String CANCEL_REASON =
            "내부 주문 처리 실패로 인한 자동 결제 취소";

    private final PaymentMapper paymentMapper;
    private final PaymentRecoveryBatchCursor batchCursor = new PaymentRecoveryBatchCursor();
    private final OrderPaymentRecoveryService orderPaymentRecoveryService;

    public CompensationRequest createRequest(Payment payment, String paymentKey) {
        if (payment == null
                || payment.getId() == null
                || payment.getOrderId() == null
                || payment.getAmount() == null
                || paymentKey == null
                || paymentKey.isBlank()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        return new CompensationRequest(
                payment.getProvider(),
                payment.getId(),
                payment.getOrderId(),
                paymentKey,
                "COMPENSATE-" + payment.getId(),
                payment.getAmount(),
                CANCEL_REASON
        );
    }

    /** 이전 confirm에서 외부 취소를 끝내지 못한 보상 요청을 찾는다. */
    @Transactional(readOnly = true)
    public Optional<CompensationRequest> findPreparedCompensation(
            Payment payment
    ) {
        if (payment == null || payment.getPaymentKey() == null || payment.getPaymentKey().isBlank()) {
            return Optional.empty();
        }
        CompensationRequest request = createRequest(payment, payment.getPaymentKey());
        PaymentCancellation cancellation = findCompensation(request);
        if (cancellation == null) {
            return Optional.empty();
        }
        validateCancellation(cancellation, request);
        return cancellation.getStatus() == PaymentCancellationStatus.REQUESTED
                ? Optional.of(request)
                : Optional.empty();
    }

    /** PG에 승인되지 않은 이전 보상 요청을 실패 처리하고 새 승인 시도를 허용한다. */
    @Transactional
    public void releaseUnapprovedCompensation(CompensationRequest request) {
        PaymentCancellation cancellation = findCompensation(request);
        if (cancellation == null
                || cancellation.getStatus() != PaymentCancellationStatus.REQUESTED
                || paymentMapper.failUnapprovedCompensationIfRequested(
                        cancellation.getId()
                ) != 1
                || paymentMapper.rotateReadyPaymentIdempotencyKey(
                        request.paymentId(),
                        request.paymentKey(),
                        nextApprovalIdempotencyKey()
                ) != 1
                || paymentMapper.clearRecoveryPaymentKey(
                        request.paymentId(),
                        request.paymentKey()
                ) != 1) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
    }

    /** 외부 취소 전에 보상 요청을 저장한다. */
    @Transactional
    public void prepareCompensation(CompensationRequest request) {
        PaymentCancellation saved = findCompensation(request);
        if (saved == null) {
            PaymentCancellation cancellation = new PaymentCancellation();
            cancellation.setPaymentId(request.paymentId());
            cancellation.setIdempotencyKey(request.idempotencyKey());
            cancellation.setCancelAmount(request.amount());
            cancellation.setCancelReason(request.reason());
            paymentMapper.insertCompensationCancellation(cancellation);
            saved = findCompensation(request);
        }
        if (saved != null && saved.getStatus() == PaymentCancellationStatus.FAILED) {
            if (paymentMapper.reopenReleasedCompensation(
                    request.paymentId(),
                    request.idempotencyKey()
            ) != 1) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
            }
            saved = findCompensation(request);
        }
        if (saved == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        validateCancellation(saved, request);
        paymentMapper.attachRecoveryPaymentKey(
                request.paymentId(),
                request.paymentKey()
        );
        Payment savedPayment = paymentMapper.findPaymentById(request.paymentId())
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.PAYMENT_RECOVERY_PENDING
                ));
        if (!request.paymentKey().equals(savedPayment.getPaymentKey())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
    }

    /** 정상 승인 완료 유예 시간이 지난 시스템 보상 요청을 조회한다. */
    @Transactional(readOnly = true)
    public List<CompensationRequest> getPreparedCompensations(int limit) {
        if (limit <= 0) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        return paymentMapper.findRequestedCompensations(limit, batchCursor.nextProvider())
                .stream()
                .map(this::toCompensationRequest)
                .toList();
    }

    /** PG 취소 성공을 결제·주문·재고에 하나의 트랜잭션으로 반영한다. */
    @Transactional
    public void completeCompensation(
            CompensationRequest request,
            CancellationResult result
    ) {
        validateResult(result);
        PaymentCancellation cancellation = findCompensation(request);
        if (cancellation == null) {
            prepareCompensation(request);
            cancellation = findCompensation(request);
        }
        if (cancellation == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        validateCancellation(cancellation, request);

        if (cancellation.getStatus() == PaymentCancellationStatus.DONE) {
            return;
        }

        int affectedRows = paymentMapper.completeCompensationIfRequested(
                cancellation.getId(),
                request.paymentKey(),
                result.transactionKey(),
                result.canceledAt()
        );
        if (affectedRows <= 0) {
            PaymentCancellation completed = findCompensation(request);
            if (completed != null
                    && completed.getStatus() == PaymentCancellationStatus.DONE) {
                return;
            }
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        orderPaymentRecoveryService.cancelAfterPaymentCompensation(
                request.orderId(),
                result.canceledAt(),
                request.reason()
        );
    }

    /** 내부 결제가 정상 완료된 승인 보호용 보상 요청을 비활성화한다. */
    @Transactional
    public void discardApprovalRecovery(Payment payment) {
        if (payment == null || payment.getId() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_COMPLETE_FAILED);
        }
        PaymentCancellation cancellation = paymentMapper
                .findPaymentCancellationByIdempotencyKey("COMPENSATE-" + payment.getId())
                .orElse(null);
        if (cancellation == null || cancellation.getStatus() == PaymentCancellationStatus.FAILED) {
            return;
        }
        if (cancellation.getStatus() != PaymentCancellationStatus.REQUESTED
                || paymentMapper.failCancellationIfRequested(
                        cancellation.getId(),
                        "PAYMENT_COMPLETED"
                ) != 1) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_COMPLETE_FAILED);
        }
    }

    private PaymentCancellation findCompensation(CompensationRequest request) {
        return paymentMapper
                .findPaymentCancellationByIdempotencyKey(request.idempotencyKey())
                .orElse(null);
    }

    private CompensationRequest toCompensationRequest(
            PaymentCancellation cancellation
    ) {
        Payment payment = paymentMapper.findPaymentById(cancellation.getPaymentId())
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.PAYMENT_RECOVERY_PENDING
                ));
        CompensationRequest request = createRequest(payment, payment.getPaymentKey());
        validateCancellation(cancellation, request);
        return request;
    }

    private void validateCancellation(
            PaymentCancellation cancellation,
            CompensationRequest request
    ) {
        if (!Long.valueOf(request.paymentId()).equals(cancellation.getPaymentId())
                || cancellation.getCancelAmount() == null
                || cancellation.getCancelAmount().compareTo(request.amount()) != 0
                || !"SYSTEM_COMPENSATION".equals(cancellation.getRequestType())
                || cancellation.getStatus() == PaymentCancellationStatus.FAILED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
    }

    private void validateResult(CancellationResult result) {
        if (result == null
                || result.status() != PaymentGatewayStatus.CANCELED
                || result.transactionKey() == null
                || result.transactionKey().isBlank()
                || result.canceledAt() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
    }

    private String nextApprovalIdempotencyKey() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "");
    }

    public record CompensationRequest(
            PaymentProvider provider,
            long paymentId,
            long orderId,
            String paymentKey,
            String idempotencyKey,
            BigDecimal amount,
            String reason
    ) {
    }
}
