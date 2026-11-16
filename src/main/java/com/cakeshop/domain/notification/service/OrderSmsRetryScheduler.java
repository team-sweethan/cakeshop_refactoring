package com.cakeshop.domain.notification.service;

import com.cakeshop.domain.member.service.MemberNotificationQueryService;
import com.cakeshop.domain.notification.mapper.NotificationMapper;
import com.cakeshop.domain.order.service.OrderNotificationRetryQueryService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSmsRetryScheduler {

    private final NotificationMapper mapper;
    private final NotificationService notifications;
    private final NotificationDeliveryService deliveries;
    private final MemberNotificationQueryService members;
    private final OrderNotificationRetryQueryService orders;
    private final Clock clock;

    @Scheduled(fixedDelay = 30000)
    public void retryFailedOrderSms() {
        var now = LocalDateTime.now(clock);
        for (var notification : mapper.findRetryableOrderNotifications(50, now.minusDays(1))) {
            Long id = notification.getId();
            try {
                Long receiverId = notification.getReceiverId();
                if (receiverId == null || !members.isMemberActive(receiverId)) {
                    deliveries.recordSkippedAttempt(id, "MEMBER_INACTIVE");
                    continue;
                }
                if (!orders.isCurrent(notification.getOrderId(), notification.getNotificationType().name(),
                        notification.getCreatedAt(), LocalDateTime.now(clock))) {
                    deliveries.recordSkippedAttempt(id, "ORDER_NOTIFICATION_OUTDATED");
                    continue;
                }
                notifications.retrySmsForNotification(id, receiverId, notification.getOrderId(),
                        notification.getTitle(), notification.getContent());
            } catch (RuntimeException exception) {
                log.warn("주문 알림 SMS 재처리 실패 (notificationId={})", id, exception);
            }
        }
    }
}
