package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import com.cakeshop.domain.payment.service.PaymentApprovalResolver.ApprovalResolution;
import com.cakeshop.global.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 승인 후 내부 처리 실패를 보상 취소하고, 재시도 가능한 로컬 상태로 반영한다. */
@Component
public class PaymentCompensationProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompensationProcessor.class);

    private final PaymentRecoveryService paymentRecoveryService;
    private final PaymentGateway paymentGateway;
    private final PaymentApprovalResolver approvalResolver;

    public PaymentCompensationProcessor(
            PaymentRecoveryService paymentRecoveryService,
            PaymentGateway paymentGateway,
            PaymentApprovalResolver approvalResolver
    ) {
        this.paymentRecoveryService = paymentRecoveryService;
        this.paymentGateway = paymentGateway;
        this.approvalResolver = approvalResolver;
    }

    public Optional<CompensationRequest> findPrepared(Payment payment) {
        return paymentRecoveryService.findPreparedCompensation(payment);
    }

    public void releaseUnapproved(CompensationRequest request) {
        paymentRecoveryService.releaseUnapprovedCompensation(request);
    }

    private List<CompensationRequest> getPreparedCompensations(int batchSize) {
        return paymentRecoveryService.getPreparedCompensations(batchSize);
    }

    public void prepareBeforeApproval(Payment payment, String paymentKey) {
        try {
            CompensationRequest request = paymentRecoveryService.createRequest(payment, paymentKey);
            paymentRecoveryService.prepareCompensation(request);
        } catch (RuntimeException persistenceFailure) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
    }

    public BusinessException compensateApproved(Payment payment, String paymentKey) {
        CompensationRequest request = paymentRecoveryService.createRequest(payment, paymentKey);
        try {
            paymentRecoveryService.prepareCompensation(request);
        } catch (RuntimeException prepareFailure) {
            log.warn("Payment compensation request could not be persisted before provider cancel.");
            return new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        execute(request);
        return new BusinessException(PaymentErrorCode.PAYMENT_COMPENSATED);
    }

    public BusinessException reconcileCanceled(Payment payment, PaymentLookupResult lookup) {
        CancellationResult result = PaymentCancellationResultResolver.findCompleted(lookup)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING));
        CompensationRequest request = paymentRecoveryService.createRequest(payment, lookup.paymentKey());
        try {
            paymentRecoveryService.prepareCompensation(request);
            paymentRecoveryService.completeCompensation(request, result);
        } catch (RuntimeException recoveryFailure) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        return new BusinessException(PaymentErrorCode.PAYMENT_COMPENSATED);
    }

    private void completePrepared(CompensationRequest request) {
        execute(request);
    }

    public BusinessException cancelPrepared(CompensationRequest request) {
        execute(request);
        return new BusinessException(PaymentErrorCode.PAYMENT_COMPENSATED);
    }

    /** 영속화된 미완료 보상 취소를 같은 멱등키로 다시 처리한다. */
    public void recoverPendingCompensations(int batchSize) {
        for (CompensationRequest request : getPreparedCompensations(batchSize)) {
            try {
                recover(request);
            } catch (RuntimeException recoveryFailure) {
                log.warn("Pending payment compensation could not be completed.");
            }
        }
    }

    private void recover(CompensationRequest request) {
        ApprovalResolution approvalState = approvalResolver.resolveCompensationState(request);
        switch (approvalState.state()) {
            case NOT_APPROVED -> releaseUnapproved(request);
            case IN_PROGRESS -> {
                return;
            }
            case DONE, CANCELED, FINAL_OR_UNKNOWN -> completePrepared(request);
        }
    }

    private void execute(CompensationRequest request) {
        CancellationResult result;
        try {
            result = paymentGateway.cancel(request.paymentKey(), request.reason(), request.idempotencyKey());
        } catch (BusinessException cancelFailure) {
            result = PaymentCancellationResultResolver.findCompleted(paymentGateway, request.paymentKey())
                    .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING));
        }

        try {
            paymentRecoveryService.completeCompensation(request, result);
        } catch (RuntimeException completionFailure) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
    }

}
