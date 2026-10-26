package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;

import java.util.Optional;

/** PG 조회 결과에서 완료된 전체 취소 결과만 추린다. */
final class PaymentCancellationResultResolver {

    private PaymentCancellationResultResolver() {
    }

    static Optional<CancellationResult> findCompleted(
            PaymentGateway paymentGateway,
            String paymentKey
    ) {
        return paymentGateway.find(paymentKey)
                .flatMap(PaymentCancellationResultResolver::findCompleted);
    }

    static Optional<CancellationResult> findCompleted(PaymentLookupResult lookup) {
        return Optional.ofNullable(lookup)
                .filter(payment -> payment.status() == PaymentGatewayStatus.CANCELED)
                .map(PaymentLookupResult::cancellation)
                .filter(PaymentCancellationResultResolver::isCompleted);
    }

    private static boolean isCompleted(CancellationResult result) {
        return result != null
                && result.status() == PaymentGatewayStatus.CANCELED
                && result.transactionKey() != null
                && !result.transactionKey().isBlank()
                && result.canceledAt() != null;
    }
}
