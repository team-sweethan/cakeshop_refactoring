package com.cakeshop.domain.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ******************************
 * 작성자 : 김민정
 * 담당자 : 주환
 * 작성일 : 2026-08-18
 * 기능 : 주문 및 결제 트랜잭션 안전 알림 연동 서비스
 * 설명 : DB 커밋 완료(afterCommit) 시점에 안전하게 OrderNotificationSender를 호출하여 트랜잭션 롤백을 방지한다.
 * ******************************
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotificationService {

    private final OrderNotificationSender orderNotificationSender;

    public void notifyOrderPaid(long orderId, long customerId, String orderType) {
        afterCommit(
                () -> orderNotificationSender.sendOrderPaid(orderId, customerId, orderType),
                "주문 결제 완료",
                orderId
        );
    }

    public void notifyCustomOrderInProduction(long orderId, long customerId) {
        afterCommit(
                () -> orderNotificationSender.sendCustomOrderInProduction(orderId, customerId),
                "주문제작 제작 승인",
                orderId
        );
    }

    public void notifyCustomOrderRejected(long orderId, long customerId) {
        afterCommit(
                () -> orderNotificationSender.sendCustomOrderRejected(orderId, customerId),
                "주문제작 반려",
                orderId
        );
    }

    public void notifyOrderCanceled(long orderId, long customerId) {
        afterCommit(
                () -> orderNotificationSender.sendOrderCanceled(orderId, customerId),
                "주문 취소 완료",
                orderId
        );
    }

    public void notifyRefundFailed(long orderId, String orderNumber) {
        afterCommit(
                () -> orderNotificationSender.sendRefundFailed(orderId, orderNumber),
                "환불 처리 실패",
                orderId
        );
    }

    /** PG(돈워리)가 결제를 확인 중인 주문을 관리자에게 알린다 (DonWorry ADR-0064 4절). */
    public void notifyPaymentUnderReview(long orderId, String orderNumber) {
        afterCommit(
                () -> orderNotificationSender.sendPaymentUnderReview(orderId, orderNumber),
                "결제 확인 중",
                orderId
        );
    }

    private void afterCommit(Runnable send, String what, long orderId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(send, what, orderId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(send, what, orderId);
            }
        });
    }

    private void dispatch(Runnable send, String what, long orderId) {
        try {
            send.run();
        } catch (Exception e) {
            log.warn("{} 알림 발송 처리 실패 (orderId: {}, 원인: {})", what, orderId, e.getClass().getSimpleName());
        }
    }
}
