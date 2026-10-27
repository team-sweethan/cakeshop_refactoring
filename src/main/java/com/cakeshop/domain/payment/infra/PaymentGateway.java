package com.cakeshop.domain.payment.infra;

import java.util.Optional;
import com.cakeshop.domain.payment.entity.PaymentProvider;

/** 결제 승인·조회·취소와 결제창 설정을 제공하는 PG 경계다. */
public interface PaymentGateway {

    PaymentProvider provider();

    boolean isAvailable();

    String clientKey();

    /** 주문명을 해당 PG 결제창의 제약에 맞게 가공한다. */
    String formatOrderName(String orderName);

    boolean isUserCanceled(String failureCode);

    boolean isCardRejected(String failureCode);

    ApprovalResult approve(String paymentKey, String orderId, long amount, String idempotencyKey);

    Optional<PaymentLookupResult> find(String paymentKey);

    CancellationResult cancel(String paymentKey, String reason, String idempotencyKey);
}
