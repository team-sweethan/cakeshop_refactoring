package com.cakeshop.domain.order.service;

import com.cakeshop.domain.member.service.MemberNotificationQueryService;
import com.cakeshop.domain.member.service.MemberOrderNotificationQueryService;
import com.cakeshop.domain.notification.dto.form.NotificationRequest;
import com.cakeshop.domain.notification.entity.DeliveryScope;
import com.cakeshop.domain.notification.entity.NotificationType;
import com.cakeshop.domain.notification.service.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ******************************
 * 작성자 : 김민정
 * 담당자 : 주환
 * 작성일 : 2026-08-18
 * 기능 : 주문, 결제, 픽업 실시간/문자 알림 전송 서비스
 * 설명 : 주문/결제/픽업 라이프사이클 이벤트 발생 시 활성 고객 및 관리자에게 독립 트랜잭션(REQUIRES_NEW)에서 안전하게 실시간 웹소켓 토스트 및 문자(SMS) 알림을 발송한다.
 * ******************************
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotificationSender {

    private final NotificationService notificationService;
    private final MemberNotificationQueryService memberNotificationQueryService;
    private final MemberOrderNotificationQueryService memberOrderNotificationQueryService;
    private final OrderPickupNotificationSender orderPickupNotificationSender;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOrderPaid(long orderId, long customerId, String orderType) {
        boolean isCustom = "CUSTOM".equalsIgnoreCase(orderType);
        NotificationType customerType = isCustom ? NotificationType.CUSTOM_ORDER_PAID : NotificationType.ORDER_PAID;
        NotificationType adminType = isCustom ? NotificationType.NEW_CUSTOM_ORDER : NotificationType.NEW_ORDER;

        if (memberNotificationQueryService.isMemberActive(customerId)) {
            notificationService.makeNotification(NotificationRequest.builder()
                    .receiverId(customerId)
                    .actorId(customerId)
                    .orderId(orderId)
                    .type(customerType)
                    .eventKey(customerType.name() + ":" + customerId + ":" + orderId)
                    .deliveryScope(DeliveryScope.WEB_AND_SMS)
                    .args(new Object[0])
                    .build());
        }

        sendToActiveAdmins(orderId, customerId, adminType, adminType.name() + ":ALL_ADMINS:" + orderId, new Object[0]);

        // 결제 알림 트랜잭션이 안전하게 커밋된 후(afterCommit), 별도 빈의 프록시를 통해 독립 트랜잭션(REQUIRES_NEW)에서 D-1 리마인더 발송
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orderPickupNotificationSender.sendInstantPickupReminderTomorrowIfEligible(orderId, customerId);
                }
            });
        } else {
            orderPickupNotificationSender.sendInstantPickupReminderTomorrowIfEligible(orderId, customerId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendCustomOrderInProduction(long orderId, long customerId) {
        if (!memberNotificationQueryService.isMemberActive(customerId)) {
            return;
        }

        notificationService.makeNotification(NotificationRequest.builder()
                .receiverId(customerId)
                .orderId(orderId)
                .type(NotificationType.CUSTOM_ORDER_IN_PRODUCTION)
                .eventKey("CUSTOM_ORDER_IN_PRODUCTION:" + customerId + ":" + orderId)
                .deliveryScope(DeliveryScope.WEB_AND_SMS)
                .args(new Object[0])
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendCustomOrderRejected(long orderId, long customerId) {
        if (!memberNotificationQueryService.isMemberActive(customerId)) {
            return;
        }

        notificationService.makeNotification(NotificationRequest.builder()
                .receiverId(customerId)
                .orderId(orderId)
                .type(NotificationType.CUSTOM_ORDER_REJECTED)
                .eventKey("CUSTOM_ORDER_REJECTED:" + customerId + ":" + orderId)
                .deliveryScope(DeliveryScope.WEB_AND_SMS)
                .args(new Object[0])
                .build());
    }

    public void sendOrderCanceled(long orderId, long customerId) {
        sendOrderCanceled(orderId, customerId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOrderCanceled(long orderId, long customerId, String orderNumber) {
        sendOrderCanceledToCustomer(orderId, customerId);
        sendOrderCanceledToAdmins(orderId, customerId, orderNumber);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOrderCanceledToCustomer(long orderId, long customerId) {
        if (!memberNotificationQueryService.isMemberActive(customerId)) {
            return;
        }

        notificationService.makeNotification(NotificationRequest.builder()
                .receiverId(customerId)
                .orderId(orderId)
                .type(NotificationType.ORDER_CANCELED)
                .eventKey("ORDER_CANCELED:" + customerId + ":" + orderId)
                .deliveryScope(DeliveryScope.WEB_AND_SMS)
                .args(new Object[0])
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOrderCanceledToAdmins(long orderId, long customerId, String orderNumber) {
        try {
            String ordNum = (orderNumber != null && !orderNumber.isBlank()) ? orderNumber : String.valueOf(orderId);
            sendToActiveAdmins(orderId, customerId, NotificationType.ORDER_CANCEL_REQUEST, "ORDER_CANCEL_REQUEST:ALL_ADMINS:" + orderId, new Object[]{ordNum});
        } catch (Exception e) {
            log.error("관리자 주문 취소 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendRefundFailed(long orderId, String orderNumber) {
        try {
            String ordNum = (orderNumber != null && !orderNumber.isBlank()) ? orderNumber : String.valueOf(orderId);
            sendToActiveAdmins(orderId, null, NotificationType.REFUND_FAILED, "REFUND_FAILED:ALL_ADMINS:" + orderId, new Object[]{ordNum});
        } catch (Exception e) {
            log.error("환불 실패 관리자 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    /**
     * ******************************
     * 작성자 : 수민
     * 담당자 : 주환
     * 작성일 : 2026-11-04
     * 기능 : PG 결제 확인 중 관리자 알림
     * 설명 : 결제는 승인됐지만 PG(돈워리)가 확인 중인 주문을 활성 관리자에게 알린다(DonWorry ADR-0064 4절).
     *        고객에게는 보내지 않는다(구매자에게는 결제 완료로 보인다).
     * ******************************
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPaymentUnderReview(long orderId, String orderNumber) {
        try {
            String ordNum = (orderNumber != null && !orderNumber.isBlank()) ? orderNumber : String.valueOf(orderId);
            sendToActiveAdmins(orderId, null, NotificationType.ADMIN_PAYMENT_UNDER_REVIEW,
                    "ADMIN_PAYMENT_UNDER_REVIEW:ALL_ADMINS:" + orderId, new Object[]{ordNum});
        } catch (Exception e) {
            log.error("결제 확인 중 관리자 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    public void sendPickupReminderTomorrow(long orderId, long customerId, String orderNumber) {
        sendPickupReminderTomorrowToCustomer(orderId, customerId);
        sendPickupReminderTomorrowToAdmins(orderId, customerId, orderNumber);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPickupReminderTomorrowToCustomer(long orderId, long customerId) {
        try {
            if (memberNotificationQueryService.isMemberActive(customerId)) {
                notificationService.makeNotification(NotificationRequest.builder()
                        .receiverId(customerId)
                        .orderId(orderId)
                        .type(NotificationType.CUSTOMER_PICKUP_REMINDER_TOMORROW)
                        .eventKey(NotificationType.CUSTOMER_PICKUP_REMINDER_TOMORROW.name() + ":" + customerId + ":" + orderId)
                        .deliveryScope(DeliveryScope.WEB_AND_SMS)
                        .args(new Object[0])
                        .build());
            }
        } catch (Exception e) {
            log.error("고객 픽업 하루 전 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPickupReminderTomorrowToAdmins(long orderId, long customerId, String orderNumber) {
        try {
            String ordNum = (orderNumber != null && !orderNumber.isBlank()) ? orderNumber : String.valueOf(orderId);
            sendToActiveAdmins(orderId, customerId, NotificationType.ADMIN_PICKUP_REMINDER_TOMORROW, NotificationType.ADMIN_PICKUP_REMINDER_TOMORROW.name() + ":ALL_ADMINS:" + orderId, new Object[]{ordNum});
        } catch (Exception e) {
            log.error("관리자 픽업 하루 전 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    public void sendPickupReminderToday(long orderId, long customerId, String orderNumber) {
        sendPickupReminderTodayToCustomer(orderId, customerId);
        sendPickupReminderTodayToAdmins(orderId, customerId, orderNumber);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPickupReminderTodayToCustomer(long orderId, long customerId) {
        try {
            if (memberNotificationQueryService.isMemberActive(customerId)) {
                notificationService.makeNotification(NotificationRequest.builder()
                        .receiverId(customerId)
                        .orderId(orderId)
                        .type(NotificationType.CUSTOMER_PICKUP_REMINDER_TODAY)
                        .eventKey(NotificationType.CUSTOMER_PICKUP_REMINDER_TODAY.name() + ":" + customerId + ":" + orderId)
                        .deliveryScope(DeliveryScope.WEB_AND_SMS)
                        .args(new Object[0])
                        .build());
            }
        } catch (Exception e) {
            log.error("고객 픽업 당일 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPickupReminderTodayToAdmins(long orderId, long customerId, String orderNumber) {
        try {
            String ordNum = (orderNumber != null && !orderNumber.isBlank()) ? orderNumber : String.valueOf(orderId);
            sendToActiveAdmins(orderId, customerId, NotificationType.ADMIN_PICKUP_REMINDER_TODAY, NotificationType.ADMIN_PICKUP_REMINDER_TODAY.name() + ":ALL_ADMINS:" + orderId, new Object[]{ordNum});
        } catch (Exception e) {
            log.error("관리자 픽업 당일 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    public void sendOrderPickedUp(long orderId, long customerId, String orderNumber) {
        sendOrderPickedUpToCustomer(orderId, customerId);
        sendOrderPickedUpToAdmins(orderId, customerId, orderNumber);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOrderPickedUpToCustomer(long orderId, long customerId) {
        try {
            if (memberNotificationQueryService.isMemberActive(customerId)) {
                notificationService.makeNotification(NotificationRequest.builder()
                        .receiverId(customerId)
                        .orderId(orderId)
                        .type(NotificationType.CUSTOMER_ORDER_PICKED_UP)
                        .eventKey(NotificationType.CUSTOMER_ORDER_PICKED_UP.name() + ":" + customerId + ":" + orderId)
                        .deliveryScope(DeliveryScope.WEB_AND_SMS)
                        .args(new Object[0])
                        .build());
            }
        } catch (Exception e) {
            log.error("고객 픽업 완료 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOrderPickedUpToAdmins(long orderId, long customerId, String orderNumber) {
        try {
            String ordNum = (orderNumber != null && !orderNumber.isBlank()) ? orderNumber : String.valueOf(orderId);
            sendToActiveAdmins(orderId, customerId, NotificationType.ADMIN_PICKEDUP, NotificationType.ADMIN_PICKEDUP.name() + ":ALL_ADMINS:" + orderId, new Object[]{ordNum});
        } catch (Exception e) {
            log.error("관리자 픽업 완료 알림 발송 오류 (orderId={}):", orderId, e);
        }
    }

    private void sendToActiveAdmins(long orderId, Long actorId, NotificationType type, String baseEventKey, Object[] args) {
        try {
            List<Long> activeAdminIds = memberOrderNotificationQueryService.findActiveAdminIds();
            if (activeAdminIds == null || activeAdminIds.isEmpty()) return;

            for (Long adminId : activeAdminIds) {
                try {
                    notificationService.makeNotification(NotificationRequest.builder()
                            .receiverId(adminId)
                            .actorId(actorId)
                            .orderId(orderId)
                            .type(type)
                            .eventKey(type.name() + ":" + adminId + ":" + orderId)
                            .deliveryScope(DeliveryScope.WEB_AND_SMS)
                            .args(args)
                            .build());
                } catch (Exception e) {
                    log.error("관리자(id={}) 대상 {} 알림 발송 중 개별 오류 발생 (orderId={}):", adminId, type, orderId, e);
                }
            }
        } catch (Exception e) {
            log.error("관리자 목록 대상 {} 알림 발송 중 오류 발생 (orderId={}):", type, orderId, e);
        }
    }
}
