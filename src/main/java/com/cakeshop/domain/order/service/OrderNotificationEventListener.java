package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.dto.view.OrderChatView;
import com.cakeshop.domain.payment.event.CustomPaymentChatCompletedEvent;
import com.cakeshop.domain.payment.event.GeneralPaymentCompletedEvent;
import com.cakeshop.domain.payment.event.PaymentUnderGatewayReviewEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ******************************
 * 작성자 : 김민정
 * 담당자 : 주환
 * 작성일 : 2026-08-18
 * 기능 : 주문 및 결제 도메인 이벤트 수신 리스너
 * 설명 : 결제 완료 도메인 이벤트를 비동기(afterCommit)로 수신하여 OrderNotificationService를 호출한다.
 * ******************************
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotificationEventListener {

    private final OrderNotificationService orderNotificationService;
    private final OrderChatQueryService orderChatQueryService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleGeneralPaymentCompleted(GeneralPaymentCompletedEvent event) {
        handlePaymentCompleted(event.orderId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCustomPaymentCompleted(CustomPaymentChatCompletedEvent event) {
        handlePaymentCompleted(event.orderId());
    }

    /** PG(돈워리)가 결제를 확인 중인 주문을 관리자에게 알린다 (DonWorry ADR-0064 4절). */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentUnderGatewayReview(PaymentUnderGatewayReviewEvent event) {
        try {
            OrderChatView orderView = orderChatQueryService.findOrder(event.orderId());
            orderNotificationService.notifyPaymentUnderReview(
                    event.orderId(),
                    orderView == null ? null : orderView.orderNumber()
            );
        } catch (Exception e) {
            log.error("결제 확인 중 알림 이벤트 수신 처리 실패 (orderId={}):", event.orderId(), e);
        }
    }

    private void handlePaymentCompleted(long orderId) {
        try {
            OrderChatView orderView = orderChatQueryService.findOrder(orderId);
            if (orderView == null || orderView.memberId() == null) {
                return;
            }

            orderNotificationService.notifyOrderPaid(orderView.id(), orderView.memberId(), orderView.orderType());
        } catch (Exception e) {
            log.error("결제 완료 알림 이벤트 수신 처리 실패 (orderId={}):", orderId, e);
        }
    }
}
