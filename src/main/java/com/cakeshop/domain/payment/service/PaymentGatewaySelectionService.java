package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentResponse;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;
import com.cakeshop.global.error.BusinessException;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : 결제창에서 고객이 고른 PG(Toss·DonWorry)를 결제에 고정
 * 설명 : 승인 시도 전 READY 결제만 바꾼다. 이미 진행 중인 결제가 있으면 바꾸지 않아 이중 청구를 막는다.
 *        - Toss: 승인 요청을 시작하면 payment_key가 기록되므로 SQL 조건(payment_key IS NULL)이 막는다.
 *        - DonWorry: 요청 즉시 PENDING·HELD가 될 수 있어 DonWorry의 최신 시도를 조회해 청구 없이 끝났을 때만 바꾼다.
 *        결제 생성 시 설정값으로 PG를 고정하던 기존 결정(pg-decisions 003)을 바꾸는 제안이다(pg-decisions 006).
 * ******************************
 */
@Service
public class PaymentGatewaySelectionService {

    private final PaymentCheckoutService paymentCheckoutService;
    private final PaymentService paymentService;
    private final PaymentGatewayRouter gatewayRouter;
    private final ObjectProvider<DonWorryPaymentClient> donWorryClientProvider;

    public PaymentGatewaySelectionService(
            PaymentCheckoutService paymentCheckoutService,
            PaymentService paymentService,
            PaymentGatewayRouter gatewayRouter,
            ObjectProvider<DonWorryPaymentClient> donWorryClientProvider
    ) {
        this.paymentCheckoutService = paymentCheckoutService;
        this.paymentService = paymentService;
        this.gatewayRouter = gatewayRouter;
        this.donWorryClientProvider = donWorryClientProvider;
    }

    public void select(long memberId, String memberEmail, long orderId, PaymentProvider provider) {
        // 회원 소유·결제 대기·결제 기한·금액 검증은 결제 화면과 같은 기준을 거친다.
        PaymentCheckoutView checkout = paymentCheckoutService.getCheckout(memberId, memberEmail, orderId);
        if (!gatewayRouter.isAvailable(provider)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        Payment payment = paymentService.getReadyPayment(orderId);
        if (payment.getProvider() == provider) {
            return;
        }
        if (payment.getProvider() == PaymentProvider.OWN) {
            requireNoDonWorryCharge(checkout.gatewayOrderId());
        }
        paymentService.changeReadyPaymentProvider(payment, provider);
    }

    private void requireNoDonWorryCharge(String orderRef) {
        DonWorryPaymentClient client = donWorryClientProvider.getIfAvailable();
        if (client == null) {
            // 연동이 꺼져 있으면 DonWorry 시도 여부를 확인할 수 없으므로 바꾸지 않는다.
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        Optional<DonWorryPaymentResponse> latest;
        try {
            latest = client.findLatestAttempt(orderRef);
        } catch (DonWorryPaymentException exception) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
        }
        if (latest.isPresent() && !latest.get().endedWithoutCharge()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_CHANGE_NOT_ALLOWED);
        }
    }
}
