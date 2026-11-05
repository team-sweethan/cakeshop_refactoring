package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;
import com.cakeshop.domain.payment.infra.ApprovalResult;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import com.cakeshop.global.error.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/** PG 결제 조회·승인 결과를 로컬 결제 정보와 대조해 해석한다. */
@Component
public class PaymentApprovalResolver {

    private final PaymentGatewayRouter gatewayRouter;

    public PaymentApprovalResolver(PaymentGatewayRouter gatewayRouter) {
        this.gatewayRouter = gatewayRouter;
    }

    public ApprovalResolution resolveCurrent(Payment payment, PaymentConfirmForm form) {
        Optional<PaymentLookupResult> lookup = gatewayRouter.forProvider(payment.getProvider()).find(form.getPaymentKey());
        if (lookup.isEmpty()) {
            return ApprovalResolution.notApproved();
        }
        return resolveLookup(payment, form, lookup.get());
    }

    public ApprovalResolution approve(Payment payment, PaymentConfirmForm form) {
        ApprovalResult approval;
        try {
            approval = gatewayRouter.forProvider(payment.getProvider()).approve(
                    form.getPaymentKey(),
                    form.getGatewayOrderId(),
                    form.getAmount().longValueExact(),
                    payment.getIdempotencyKey()
            );
        } catch (BusinessException approvalFailure) {
            try {
                return resolveCurrent(payment, form);
            } catch (BusinessException lookupFailure) {
                return ApprovalResolution.finalOrUnknown();
            }
        }
        validateApproval(payment, form, approval);
        return ApprovalResolution.done(approval, null);
    }

    public ApprovalResolution resolveCompensationState(CompensationRequest request) {
        try {
            Optional<PaymentLookupResult> lookup = gatewayRouter.forProvider(request.provider()).find(request.paymentKey());
            if (lookup.isEmpty()) {
                return ApprovalResolution.notApproved();
            }
            PaymentLookupResult paymentLookup = lookup.get();
            return switch (paymentLookup.status()) {
                // 청구 없이 끝났더라도 승인 뒤 PG가 승인을 취소한 기록이 있으면(돈워리 VOIDED) 보상 취소가 끝난 것이다.
                case READY, ABORTED, EXPIRED -> approvalCanceledByGateway(paymentLookup)
                        .map(ApprovalResolution::canceled)
                        .orElseGet(ApprovalResolution::notApproved);
                case IN_PROGRESS -> ApprovalResolution.inProgress();
                case DONE -> ApprovalResolution.done(paymentLookup.toApprovalResult(), paymentLookup);
                case CANCELED -> ApprovalResolution.canceled(paymentLookup);
                default -> ApprovalResolution.finalOrUnknown();
            };
        } catch (BusinessException lookupFailure) {
            return ApprovalResolution.finalOrUnknown();
        }
    }

    /**
     * 승인 취소 기록이 있는 청구 없음 결과를 취소 완료 조회 결과로 바꾼다. Toss의 미승인 결과에는 취소 기록이 없어 해당하지 않는다.
     * 보상 취소 복구 판단에만 쓴다(일반 승인 확인에서는 지금처럼 승인 실패로 본다).
     */
    private static Optional<PaymentLookupResult> approvalCanceledByGateway(PaymentLookupResult lookup) {
        if (lookup.cancellation() == null
                || lookup.cancellation().status() != PaymentGatewayStatus.CANCELED) {
            return Optional.empty();
        }
        return Optional.of(new PaymentLookupResult(
                lookup.paymentKey(),
                lookup.orderId(),
                lookup.method(),
                PaymentGatewayStatus.CANCELED,
                lookup.totalAmount(),
                lookup.approvedAt(),
                lookup.cancellation(),
                lookup.heldForReview()
        ));
    }

    private void validateApproval(Payment payment, PaymentConfirmForm form, ApprovalResult approval) {
        if (approval == null
                || !payment.getGatewayOrderId().equals(approval.orderId())
                || approval.status() != PaymentGatewayStatus.DONE
                || payment.getAmount().compareTo(BigDecimal.valueOf(approval.totalAmount())) != 0
                || approval.paymentKey() == null
                || !form.getPaymentKey().equals(approval.paymentKey())
                || approval.approvedAt() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        }
    }

    private ApprovalResolution resolveLookup(
            Payment payment,
            PaymentConfirmForm form,
            PaymentLookupResult lookup
    ) {
        validateLookup(payment, form, lookup);
        return switch (lookup.status()) {
            case DONE -> ApprovalResolution.done(lookup.toApprovalResult(), lookup);
            case CANCELED -> ApprovalResolution.canceled(lookup);
            case READY -> ApprovalResolution.notApproved();
            case IN_PROGRESS -> ApprovalResolution.inProgress();
            default -> throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        };
    }

    private void validateLookup(Payment payment, PaymentConfirmForm form, PaymentLookupResult lookup) {
        if (lookup == null || !form.getPaymentKey().equals(lookup.paymentKey())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        }
        if (!payment.getGatewayOrderId().equals(lookup.orderId())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_ID_MISMATCH);
        }
        if (payment.getAmount().compareTo(BigDecimal.valueOf(lookup.totalAmount())) != 0) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
    }

    public enum ApprovalState {
        NOT_APPROVED,
        IN_PROGRESS,
        DONE,
        CANCELED,
        FINAL_OR_UNKNOWN
    }

    public record ApprovalResolution(
            ApprovalState state,
            ApprovalResult approval,
            PaymentLookupResult lookup
    ) {

        static ApprovalResolution notApproved() {
            return new ApprovalResolution(ApprovalState.NOT_APPROVED, null, null);
        }

        static ApprovalResolution inProgress() {
            return new ApprovalResolution(ApprovalState.IN_PROGRESS, null, null);
        }

        static ApprovalResolution done(ApprovalResult approval, PaymentLookupResult lookup) {
            return new ApprovalResolution(ApprovalState.DONE, approval, lookup);
        }

        static ApprovalResolution canceled(PaymentLookupResult lookup) {
            return new ApprovalResolution(ApprovalState.CANCELED, null, lookup);
        }

        static ApprovalResolution finalOrUnknown() {
            return new ApprovalResolution(ApprovalState.FINAL_OR_UNKNOWN, null, null);
        }
    }
}
