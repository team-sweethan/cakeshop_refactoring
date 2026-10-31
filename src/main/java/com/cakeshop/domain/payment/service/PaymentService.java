package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentCommandService;
import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentExecutionOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentProduct;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.ApprovalResult;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.event.CustomPaymentChatCompletedEvent;
import com.cakeshop.domain.payment.event.GeneralPaymentCompletedEvent;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** READY 결제 조회와 승인 후 내부 결제 상태 확정을 담당한다. */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final ProductStockService productStockService;
    private final OrderPaymentCommandService orderPaymentCommandService;
    private final PaymentRecoveryService paymentRecoveryService;
    private final Clock clock;
    // 쿠폰 담당자의 공개 계약으로 결제 성공 시 RESERVED 쿠폰을 USED로 확정한다.
    private final CouponOrderCommandService couponOrderCommandService;
    private final ApplicationEventPublisher eventPublisher;

    /** 주문의 현재 READY 결제를 조회한다. */
    @Transactional(readOnly = true)
    public Payment getReadyPayment(long orderId) {
        return paymentMapper.findReadyPaymentByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.READY_PAYMENT_NOT_FOUND));
    }

    /** 승인 시도 전 READY 결제의 PG를 고객이 고른 PG로 바꾼다. */
    @Transactional
    public void changeReadyPaymentProvider(Payment payment, PaymentProvider provider) {
        if (paymentMapper.changeReadyPaymentProvider(payment.getId(), provider) != 1) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_CHANGE_NOT_ALLOWED);
        }
    }

    /** 실패·만료를 포함한 주문의 마지막 결제 기록을 조회한다. */
    @Transactional(readOnly = true)
    public Payment getLatestPayment(long orderId) {
        return paymentMapper.findLatestPaymentByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.READY_PAYMENT_NOT_FOUND));
    }

    /** 중복 성공 콜백과 완료 화면 검증을 위해 완료 결제를 조회한다. */
    @Transactional(readOnly = true)
    public Optional<Payment> findDonePayment(long orderId) {
        return paymentMapper.findDonePaymentByOrderId(orderId);
    }

    /** 완료 화면에 필요한 DONE 결제를 조회한다. */
    @Transactional(readOnly = true)
    public Payment getDonePayment(long orderId) {
        return findDonePayment(orderId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.COMPLETED_PAYMENT_NOT_FOUND
                ));
    }

    /** 재고 차감, 결제 완료, 주문 유형별 상태 변경을 하나의 트랜잭션으로 확정한다. */
    @Transactional
    public void completePayment(
            PaymentExecutionOrder order,
            Payment payment,
            ApprovalResult approval
    ) {
        if (order.orderType() == OrderType.GENERAL) {
            completeGeneralPayment(order, payment, approval);
            return;
        }
        if (order.orderType() == OrderType.CUSTOM) {
            completeCustomPayment(order, payment, approval);
            return;
        }
        throw new BusinessException(PaymentErrorCode.PAYMENT_COMPLETE_FAILED);
    }

    /** 일반 주문 결제 완료 흐름은 주문 유형 dispatcher 내부에서만 호출한다. */
    private void completeGeneralPayment(
            PaymentExecutionOrder order,
            Payment payment,
            ApprovalResult approval
    ) {
        orderPaymentCommandService.lockGeneralOrderForPayment(order.orderId());
        validatePaymentExpiration(order.paymentExpiresAt());

        deductStockAndRecord(order.products(), approval.approvedAt());

        requireOneRow(paymentMapper.completeIfReady(
                payment.getId(),
                approval.paymentKey(),
                approval.method(),
                approval.approvedAt()
        ));

        orderPaymentCommandService.completeGeneralOrderAfterPayment(
                order.orderId(),
                approval.approvedAt()
        );
        couponOrderCommandService.useReservedCouponForOrder(order.orderId());
        paymentRecoveryService.discardApprovalRecovery(payment);
        // 장바구니에서 생성한 일반 주문만 결제 커밋 후 연결된 장바구니 항목을 정리한다.
        eventPublisher.publishEvent(new GeneralPaymentCompletedEvent(order.orderId()));
    }

    private void completeCustomPayment(
            PaymentExecutionOrder order,
            Payment payment,
            ApprovalResult approval
    ) {
        // 주문 행을 먼저 잠가 스케줄러의 EXPIRED 전이와 동일한 잠금 순서를 사용한다.
        orderPaymentCommandService.lockOrderForPayment(order.orderId());
        validatePaymentExpiration(order.paymentExpiresAt());

        // products는 주문 생성 시점의 상품 유형을 유지하는 주문 항목 스냅샷이다.
        deductStockAndRecord(order.products(), approval.approvedAt());

        requireOneRow(paymentMapper.completeIfReady(
                payment.getId(),
                approval.paymentKey(),
                approval.method(),
                approval.approvedAt()
        ));

        orderPaymentCommandService.completeCustomOrderAfterPayment(
                order.orderId(),
                approval.approvedAt()
        );
        // 주문·결제 완료가 같은 트랜잭션에서 성공한 뒤에만 쿠폰 사용을 확정한다.
        couponOrderCommandService.useReservedCouponForOrder(order.orderId());
        paymentRecoveryService.discardApprovalRecovery(payment);
        eventPublisher.publishEvent(new CustomPaymentChatCompletedEvent(order.orderId()));
    }

    /** PG 호출 없이 0원 주문의 재고·결제·주문·쿠폰 상태를 같은 트랜잭션에서 완료한다. */
    @Transactional
    public void completeZeroAmountGeneralPayment(
            PaymentExecutionOrder order,
            Payment payment,
            LocalDateTime completedAt
    ) {
        if (order.amount().compareTo(BigDecimal.ZERO) != 0
                || payment.getAmount() == null
                || payment.getAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }

        orderPaymentCommandService.lockGeneralOrderForPayment(order.orderId());
        deductStockAndRecord(order.products(), completedAt);
        requireOneRow(paymentMapper.completeZeroAmountIfReady(payment.getId(), completedAt));
        orderPaymentCommandService.completeGeneralOrderAfterPayment(order.orderId(), completedAt);
        couponOrderCommandService.useReservedCouponForOrder(order.orderId());
        eventPublisher.publishEvent(new GeneralPaymentCompletedEvent(order.orderId()));
    }

    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new BusinessException(
                    PaymentErrorCode.PAYMENT_COMPLETE_FAILED
            );
        }
    }

    private void deductStockAndRecord(
            List<PaymentProduct> products,
            LocalDateTime deductedAt
    ) {
        for (PaymentProduct product : products) {
            if (productStockService.decreaseStock(product.productId(), product.quantity())) {
                orderPaymentCommandService.recordStockDeduction(
                        product.orderItemId(),
                        deductedAt
                );
            }
        }
    }

    private void validatePaymentExpiration(LocalDateTime paymentExpiresAt) {
        if (paymentExpiresAt == null || !LocalDateTime.now(clock).isBefore(paymentExpiresAt)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_EXPIRED);
        }
    }
}
