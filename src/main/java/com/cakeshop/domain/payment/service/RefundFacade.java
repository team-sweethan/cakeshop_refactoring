package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.service.RefundService.RefundRequest;
import com.cakeshop.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/** 외부 PG 취소와 내부 환불 완료 트랜잭션을 조정한다. */
@Service
@RequiredArgsConstructor
public class RefundFacade {

    private static final Logger log = LoggerFactory.getLogger(RefundFacade.class);

    private final RefundService refundService;
    private final PaymentGateway paymentGateway;

    public void cancelCustomerOrder(long memberId, long orderId, String reason) {
        if (refundService.cancelCustomerZeroAmountOrder(memberId, orderId, reason)) {
            return;
        }
        RefundRequest request = refundService.prepareCustomerCancellation(
                memberId,
                orderId,
                reason
        );
        cancel(request);
    }

    public void cancelAdminOrder(long adminMemberId, long orderId, String reason) {
        if (refundService.cancelAdminZeroAmountOrder(adminMemberId, orderId, reason)) {
            return;
        }
        RefundRequest request = refundService.prepareAdminCancellation(
                adminMemberId,
                orderId,
                reason
        );
        cancel(request);
    }

    /** 관리자가 승인 전 수제 주문을 반려하고 결제를 전액 환불한다. */
    public void rejectCustomOrder(long adminMemberId, long orderId, String reason) {
        if (refundService.rejectCustomZeroAmountOrder(adminMemberId, orderId, reason)) {
            return;
        }
        RefundRequest request = refundService.prepareAdminRejection(
                adminMemberId,
                orderId,
                reason
        );
        cancel(request);
    }

    /** 남은 고객·관리자 취소 요청을 같은 멱등키로 재시도한다. */
    public void recoverPendingCancellations(int batchSize) {
        for (RefundRequest request : refundService.getRequestedCancellations(batchSize)) {
            try {
                cancel(request);
            } catch (RuntimeException recoveryFailure) {
                log.warn("Pending refund cancellation could not be completed.");
            }
        }
    }

    private void cancel(RefundRequest request) {
        CancellationResult result;
        try {
            result = paymentGateway.cancel(
                    request.paymentKey(),
                    request.reason(),
                    request.idempotencyKey()
            );
        } catch (BusinessException exception) {
            result = findCanceledResult(request.paymentKey())
                    .orElseThrow(() -> new BusinessException(
                            PaymentErrorCode.PAYMENT_RECOVERY_PENDING
                    ));
        }
        // PG 성공 뒤 내부 확정이 실패하면 REQUESTED를 유지해 같은 멱등 키로 재처리할 수 있다.
        refundService.completeCancellation(request, result);
    }

    private Optional<CancellationResult> findCanceledResult(String paymentKey) {
        try {
            return PaymentCancellationResultResolver.findCompleted(paymentGateway, paymentKey);
        } catch (BusinessException lookupFailure) {
            return Optional.empty();
        }
    }
}
