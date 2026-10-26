package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrder;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.payment.dto.form.TossPaymentSuccessForm;
import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.dto.view.PaymentCompletionView;
import com.cakeshop.domain.payment.dto.view.PaymentFailureView;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** 회원 소유권과 결제 상태를 검증해 결제 화면용 조회 결과를 구성한다. */
@Service
@RequiredArgsConstructor
public class PaymentCheckoutService {

    private final OrderPaymentQueryService orderPaymentQueryService;
    private final MemberService memberService;
    private final PaymentService paymentService;
    private final StoreService storeService;
    private final Clock clock;
    private final PaymentGateway paymentGateway;

    /** 결제 기한과 결제 정보를 검증해 결제 대상 주문용 Toss 결제 화면 데이터를 구성한다. */
    @Transactional(readOnly = true)
    public PaymentCheckoutView getCheckout(long memberId, String memberEmail, long orderId) {
        if (!memberService.isActiveMember(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        PaymentOrder order = orderPaymentQueryService.getMemberPaymentOrder(memberId, orderId);
        validatePendingPaymentOrder(order);
        validatePaymentExpiration(order.paymentExpiresAt());

        Payment payment = paymentService.getReadyPayment(orderId);
        validateReadyPayment(order, payment);

        List<PaymentCheckoutView.ItemView> items = order.items().stream()
                .map(item -> new PaymentCheckoutView.ItemView(
                        item.productName(),
                        item.quantity(),
                        item.totalAmount(),
                        item.options().stream()
                                .map(option -> new PaymentCheckoutView.OptionView(
                                        option.groupName(),
                                        option.optionName()
                                ))
                                .toList()
                ))
                .toList();

        return new PaymentCheckoutView(
                order.orderId(),
                order.orderNumber(),
                payment.getTossOrderId(),
                createOrderName(order),
                order.originalAmount(),
                order.discountAmount(),
                order.finalAmount(),
                order.pickupAt(),
                order.paymentExpiresAt(),
                order.ordererName(),
                memberEmail,
                order.ordererPhone(),
                paymentGateway.clientKey(),
                paymentGateway.isAvailable(),
                items
        );
    }

    /**
     * Toss 성공 리다이렉트 요청이 회원 소유의 주문·결제 정보와 일치하는지 검증한다.
     * 완료 결제는 동일 요청의 재시도 여부를, 미완료 결제는 승인 전 조건을 확인한다.
     */
    @Transactional(readOnly = true)
    public void validateSuccessCallback(long memberId, long orderId, TossPaymentSuccessForm form) {

        PaymentOrder order = orderPaymentQueryService.getMemberPaymentOrder(memberId, orderId);
        validatePaymentOrder(order);
        validateSuccessRedirectOrder(order, form);

        Payment completedPayment = paymentService.findDonePayment(orderId)
                .orElse(null);
        if (completedPayment != null) {
            validateCompletedPayment(completedPayment, form);
            return;
        }

        validatePendingPaymentOrder(order);
        validatePaymentExpiration(order.paymentExpiresAt());
        validateReadyPayment(order, paymentService.getReadyPayment(orderId));
    }

    /** 결제 실패 안내와 재결제 가능 여부를 조회한다. */
    @Transactional(readOnly = true)
    public PaymentFailureView getFailure(long memberId, long orderId, String failureCode) {

        PaymentOrder order = orderPaymentQueryService.getMemberPaymentOrder(memberId, orderId);
        validatePaymentOrder(order);

        boolean retryAvailable = order.pendingPayment()
                && order.paymentExpiresAt() != null
                && LocalDateTime.now(clock).isBefore(order.paymentExpiresAt());

        return new PaymentFailureView(
                orderId,
                failureMessage(failureCode),
                retryAvailable
        );
    }

    @Transactional(readOnly = true)
    public PaymentCompletionView getCompletion(long memberId, long orderId) {
        PaymentOrder order = orderPaymentQueryService.getMemberPaymentOrder(memberId, orderId);
        validatePaymentOrder(order);
        Payment payment = paymentService.getDonePayment(orderId);

        if (!sameAmount(order.finalAmount(), payment.getAmount())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }

        return new PaymentCompletionView(
                order.orderId(),
                order.orderNumber(),
                payment.getAmount(),
                payment.getMethod(),
                order.pickupAt(),
                storeService.getStoreView().pickupPlace(),
                order.items().stream()
                        .map(item -> new PaymentCompletionView.ItemView(
                                item.productName(),
                                item.quantity()
                        ))
                        .toList()
        );
    }

    private void validatePaymentOrder(PaymentOrder order) {
        if (order.orderType() == null) {
            throw new BusinessException(PaymentErrorCode.READY_PAYMENT_NOT_FOUND);
        }
    }

    private void validatePendingPaymentOrder(PaymentOrder order) {
        validatePaymentOrder(order);
        if (!order.pendingPayment()) {
            throw new BusinessException(PaymentErrorCode.READY_PAYMENT_NOT_FOUND);
        }
    }

    private void validatePaymentExpiration(LocalDateTime paymentExpiresAt) {
        if (paymentExpiresAt == null || !LocalDateTime.now(clock).isBefore(paymentExpiresAt)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_EXPIRED);
        }
    }
    private void validateReadyPayment(PaymentOrder order, Payment payment) {
        if (payment.getTossOrderId() == null || !payment.getTossOrderId().equals(order.orderNumber())) {
            throw new BusinessException(PaymentErrorCode.TOSS_ORDER_ID_MISMATCH);
        }
        if (!sameAmount(order.finalAmount(), payment.getAmount())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
    }

    private void validateSuccessRedirectOrder(PaymentOrder order, TossPaymentSuccessForm form) {

        if (form == null || !order.orderNumber().equals(form.getOrderId())) {
            throw new BusinessException(PaymentErrorCode.TOSS_ORDER_ID_MISMATCH);
        }
        if (!sameAmount(order.finalAmount(), form.getAmount())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
    }

    private void validateCompletedPayment(
            Payment payment,
            TossPaymentSuccessForm form
    ) {
        if (!payment.getTossOrderId().equals(form.getOrderId())) {
            throw new BusinessException(PaymentErrorCode.TOSS_ORDER_ID_MISMATCH);
        }
        if (!sameAmount(payment.getAmount(), form.getAmount())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        if (payment.getPaymentKey() == null
                || !payment.getPaymentKey().equals(form.getPaymentKey())) {
            throw new BusinessException(PaymentErrorCode.TOSS_APPROVAL_FAILED);
        }
    }

    private String createOrderName(PaymentOrder order) {
        if (order.items().isEmpty()) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
        String firstProductName = order.items().getFirst().productName();
        String orderName = order.items().size() == 1
                ? firstProductName
                : firstProductName + " 외 " + (order.items().size() - 1) + "건";
        return paymentGateway.formatOrderName(orderName);
    }

    private String failureMessage(String failureCode) {
        if (paymentGateway.isUserCanceled(failureCode)) {
            return "결제가 취소되었습니다. 결제 제한 시간 안에 다시 시도할 수 있습니다.";
        }
        if (paymentGateway.isCardRejected(failureCode)) {
            return "카드사에서 결제를 승인하지 않았습니다. 다른 결제수단을 이용해 주세요.";
        }
        return "결제를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    private boolean sameAmount(BigDecimal expected, BigDecimal actual) {
        return expected != null
                && actual != null
                && expected.compareTo(actual) == 0;
    }
}
