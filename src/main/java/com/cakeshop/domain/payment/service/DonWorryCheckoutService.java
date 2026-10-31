package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrder;
import com.cakeshop.domain.payment.dto.form.DonWorryPayForm;
import com.cakeshop.domain.payment.dto.view.DonWorryCheckoutView;
import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentGateway;
import com.cakeshop.domain.payment.infra.DonWorryPaymentRequest;
import com.cakeshop.domain.payment.infra.DonWorryPaymentResponse;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제창의 결제 요청·결과 확인
 * 설명 : 주문 소유권·결제 기한·금액 검증은 기존 PaymentCheckoutService를 그대로 거친다.
 *        DonWorry가 매입 완료(CAPTURED)를 알려 주면 기존 성공 콜백 → confirm 흐름으로 주문을 완료한다.
 *        이 서비스는 로컬 결제 상태를 바꾸지 않는다.
 * ******************************
 */
@Service
public class DonWorryCheckoutService {

    // DonWorry는 orderCreatedAt + 10분 안의 요청만 받는다(DonWorry #149).
    private static final Duration DONWORRY_REQUEST_WINDOW = Duration.ofMinutes(10);

    private final PaymentCheckoutService paymentCheckoutService;
    private final OrderPaymentQueryService orderPaymentQueryService;
    private final ObjectProvider<DonWorryPaymentClient> clientProvider;
    private final Clock clock;

    public DonWorryCheckoutService(
            PaymentCheckoutService paymentCheckoutService,
            OrderPaymentQueryService orderPaymentQueryService,
            ObjectProvider<DonWorryPaymentClient> clientProvider,
            Clock clock
    ) {
        this.paymentCheckoutService = paymentCheckoutService;
        this.orderPaymentQueryService = orderPaymentQueryService;
        this.clientProvider = clientProvider;
        this.clock = clock;
    }

    /** 결제창을 열 때마다 새 멱등 키를 만든다. 같은 창에서 다시 제출하면 같은 결제 시도다. */
    public DonWorryCheckoutView openWindow(long memberId, String memberEmail, long orderId, String selectedCard) {
        PaymentCheckoutView payment = getDonWorryCheckout(memberId, memberEmail, orderId);
        String selected = DonWorryDemoCard.from(selectedCard).orElse(DonWorryDemoCard.A).code();
        return new DonWorryCheckoutView(
                payment,
                Arrays.asList(DonWorryDemoCard.values()),
                selected,
                UUID.randomUUID().toString()
        );
    }

    public DonWorryPaymentResult pay(
            long memberId,
            String memberEmail,
            long orderId,
            DonWorryPayForm form,
            String deviceId,
            String ipAddress
    ) {
        PaymentCheckoutView payment = getDonWorryCheckout(memberId, memberEmail, orderId);
        DonWorryDemoCard card = DonWorryDemoCard.from(form.getCard())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT));
        DonWorryPaymentRequest request = new DonWorryPaymentRequest(
                payment.gatewayOrderId(),
                Long.toString(memberId),
                payment.amount().longValueExact(),
                orderCreatedAt(payment),
                deviceId,
                card.token(memberId),
                card.displayLabel(),
                null,
                ipAddress,
                buyerEmail(memberEmail)
        );
        DonWorryPaymentResponse response;
        try {
            response = client().request(request, form.getIdempotencyKey());
        } catch (DonWorryPaymentException exception) {
            if (exception.resultUnknown()) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
            }
            throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        }
        return toResult(orderId, payment.gatewayOrderId(), payment.amount(), response);
    }

    /** 회원 소유 주문의 DonWorry 결제인지 확인하고 현재 상태를 조회한다. 조회는 결제를 다시 실행하지 않는다. */
    public DonWorryPaymentResult status(long memberId, long orderId, String paymentKey) {
        PaymentOrder order = orderPaymentQueryService.getMemberPaymentOrder(memberId, orderId);
        long paymentId = DonWorryPaymentGateway.paymentId(paymentKey)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT));
        DonWorryPaymentResponse response;
        try {
            response = client().find(paymentId);
        } catch (DonWorryPaymentException exception) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
        }
        if (!order.orderNumber().equals(response.orderRef())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_ID_MISMATCH);
        }
        if (order.finalAmount() == null
                || order.finalAmount().compareTo(BigDecimal.valueOf(response.amount())) != 0) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        return toResult(orderId, order.orderNumber(), order.finalAmount(), response);
    }

    private PaymentCheckoutView getDonWorryCheckout(long memberId, String memberEmail, long orderId) {
        PaymentCheckoutView payment = paymentCheckoutService.getCheckout(memberId, memberEmail, orderId);
        if (!payment.donWorryCheckout()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        if (payment.amount() == null || payment.amount().signum() <= 0) {
            // 0원 주문은 기존 결제 화면의 0원 완료로만 처리한다.
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        return payment;
    }

    /**
     * DonWorry 기한(orderCreatedAt + 10분)을 CakeShop 결제 기한과 같게 맞춘다.
     * 결제 기한에서 역산하므로 같은 주문의 모든 시도에서 같은 값이다.
     */
    private Instant orderCreatedAt(PaymentCheckoutView payment) {
        Instant windowStart = payment.paymentExpiresAt()
                .atZone(clock.getZone())
                .toInstant()
                .minus(DONWORRY_REQUEST_WINDOW);
        Instant now = clock.instant();
        // DonWorry는 미래 시각을 거절한다. CakeShop 결제 기한이 10분보다 길 때만 해당한다.
        return windowStart.isAfter(now) ? now : windowStart;
    }

    private String buyerEmail(String memberEmail) {
        if (memberEmail == null || memberEmail.length() > 254 || !memberEmail.contains("@")
                || !memberEmail.strip().equals(memberEmail)) {
            return null;
        }
        return memberEmail;
    }

    private DonWorryPaymentClient client() {
        DonWorryPaymentClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        return client;
    }

    private DonWorryPaymentResult toResult(
            long orderId,
            String gatewayOrderId,
            BigDecimal amount,
            DonWorryPaymentResponse response
    ) {
        return new DonWorryPaymentResult(
                orderId,
                gatewayOrderId,
                amount,
                DonWorryPaymentGateway.paymentKey(response.paymentId()),
                response.paymentStatus(),
                response.declineCode()
        );
    }

    public record DonWorryPaymentResult(
            long orderId,
            String gatewayOrderId,
            BigDecimal amount,
            String paymentKey,
            String paymentStatus,
            String declineCode
    ) {

        public boolean captured() {
            return "CAPTURED".equals(paymentStatus);
        }

        public boolean inProgress() {
            return "PENDING".equals(paymentStatus) || "HELD".equals(paymentStatus);
        }

        /** 실패 화면으로 넘길 코드. 거절 사유의 세부 규칙은 DonWorry가 알려 주지 않는다. */
        public String failureCode() {
            if ("DECLINED".equals(paymentStatus)) {
                return DonWorryPaymentGateway.AUTHORIZATION_DECLINED;
            }
            return declineCode == null ? paymentStatus : declineCode;
        }
    }
}
