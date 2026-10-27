package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import java.util.concurrent.atomic.AtomicInteger;

/** 환불·보상 각각의 배치에서 홀수 몫과 한 건 상한의 우선 PG를 순환한다. */
final class PaymentRecoveryBatchCursor {

    private final AtomicInteger next = new AtomicInteger();

    PaymentProvider nextProvider() {
        PaymentProvider[] providers = PaymentProvider.values();
        return providers[next.getAndUpdate(index -> (index + 1) % providers.length)];
    }
}
