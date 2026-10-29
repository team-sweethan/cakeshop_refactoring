package com.cakeshop.domain.notification.service;

import com.cakeshop.domain.member.service.MemberNotificationQueryService;
import com.cakeshop.domain.notification.dto.form.NotificationRequest;
import com.cakeshop.domain.notification.dto.view.NotificationResponse;
import com.cakeshop.domain.notification.entity.DeliveryScope;
import com.cakeshop.domain.notification.entity.Notification;
import com.cakeshop.domain.notification.entity.NotificationType;
import com.cakeshop.domain.notification.event.CustomOrderRejectedChatEvent;
import com.cakeshop.domain.notification.mapper.NotificationMapper;
import com.cakeshop.global.infra.kakao.SolapiKakaoAlimtalkClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final Clock clock;
    private final MemberNotificationQueryService memberNotificationQueryService;
    private final NotificationDeliveryService notificationDeliveryService;
    private final SolapiKakaoAlimtalkClient solapiKakaoAlimtalkClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;

    // 알림 생성 메인 로직 (신규 생성, 동시성 멱등 제어, 묶음 갱신, 웹소켓/SMS 독립 후처리)
    @Transactional
    public void makeNotification(NotificationRequest request) {
        if (request == null || request.getType() == null) {
            return;
        }

        // 제목 및 본문 템플릿 포맷팅
        String title = request.getType().getDefaultTitle();
        String content = request.getType().formatContent(request.getArgs());
        DeliveryScope scope = request.getDeliveryScope() != null ? request.getDeliveryScope() : DeliveryScope.WEB_ONLY;

        // 알림 고유 이벤트 키 결정
        String eventKey = request.getEventKey();
        if (eventKey == null || eventKey.trim().isEmpty()) {
            eventKey = request.getType().name() + ":" + request.getReceiverId() + ":" + System.currentTimeMillis();
        }

        boolean isBundleNotification = request.getChatRoomId() != null
                || (eventKey != null && (eventKey.contains(":ROOM_") || eventKey.contains("ROOM_")
                || eventKey.startsWith("COMMENT_POST_") || eventKey.startsWith("REPLY_COMMENT_")));

        // 멱등성 사전 검사: 이미 동일 eventKey의 알림이 존재하는 경우
        if (notificationMapper.existsByReceiverIdAndEventKey(request.getReceiverId(), eventKey)) {
            if (isBundleNotification) {
                Notification existing = notificationMapper.findNotificationByReceiverAndEventKeyForUpdate(request.getReceiverId(), eventKey);
                LocalDateTime now = LocalDateTime.now(clock);
                notificationMapper.updateLastEventAtAndUnread(request.getReceiverId(), eventKey, title, content, now);

                long minutesGap = (existing != null && existing.getLastEventAt() != null)
                    ? Duration.between(existing.getLastEventAt(), now).toMinutes() : 999;

                Long bundleId = existing != null ? existing.getId() : notificationMapper.findIdByReceiverIdAndEventKey(request.getReceiverId(), eventKey);
                NotificationResponse bundleResponse = NotificationResponse.builder()
                        .id(bundleId)
                        .type(request.getType())
                        .title(title)
                        .content(content)
                        .isRead(false)
                        .createdAt(existing != null ? existing.getCreatedAt() : now)
                        .orderId(request.getOrderId())
                        .chatRoomId(request.getChatRoomId())
                        .commentId(request.getCommentId())
                        .postId(request.getPostId())
                        .reviewId(request.getReviewId())
                        .userCouponId(request.getUserCouponId())
                        .lastEventAt(now)
                        .build();
                registerWebSocketSending(request.getReceiverId(), bundleResponse);

                if (minutesGap >= 30 && scope == DeliveryScope.WEB_AND_SMS && request.getReceiverId() != null) {
                    String receiverPhone = notificationMapper.findReceiverPhone(request.getReceiverId(), request.getOrderId());
                    registerSmsSending(bundleId, receiverPhone, title, content, Integer.MAX_VALUE);
                }
            } else {
                // 일반 알림(주문/쿠폰 등) 중복 시 웹 토스트는 이미 최초 생성 시 안전하게 전파되었으므로 중복 토스트를 띄우지 않고, 기존 SMS 미완료 시에만 재발송
                if (scope == DeliveryScope.WEB_AND_SMS && request.getReceiverId() != null) {
                    Long existingId = notificationMapper.findIdByReceiverIdAndEventKey(request.getReceiverId(), eventKey);
                    if (existingId != null && !notificationMapper.hasSentDelivery(existingId)
                            && notificationMapper.countDeliveryAttempts(existingId) < 2) {
                        String receiverPhone = notificationMapper.findReceiverPhone(request.getReceiverId(), request.getOrderId());
                        registerSmsSending(existingId, receiverPhone, title, content, 2);
                    }
                }
            }
            return;
        }

        LocalDateTime createNow = LocalDateTime.now(clock);

        // 신규 알림 생성
        Notification notification = Notification.builder()
            .receiverId(request.getReceiverId())
            .actorId(request.getActorId())
            .orderId(request.getOrderId())
            .chatRoomId(request.getChatRoomId())
            .chatMessageId(request.getChatMessageId())
            .postId(request.getPostId())
            .commentId(request.getCommentId())
            .reviewId(request.getReviewId())
            .reviewReplyId(request.getReviewReplyId())
            .userCouponId(request.getUserCouponId())
            .notificationType(request.getType())
            .title(title)
            .content(content)
            .deliveryScope(scope)
            .isRead(false)
            .eventKey(eventKey)
            .createdAt(createNow)
            .lastEventAt(createNow)
            .build();

        // 알림 DB 저장하기 (동시 요청으로 인한 중복 키 예외 멱등 처리)
        try {
            notificationMapper.save(notification);
        } catch (DuplicateKeyException e) {
            if (isBundleNotification) {
                Notification existing = notificationMapper.findNotificationByReceiverAndEventKeyForUpdate(request.getReceiverId(), eventKey);
                LocalDateTime updateNow = LocalDateTime.now(clock);
                notificationMapper.updateLastEventAtAndUnread(request.getReceiverId(), eventKey, title, content, updateNow);

                long minutesGap = (existing != null && existing.getLastEventAt() != null)
                    ? Duration.between(existing.getLastEventAt(), updateNow).toMinutes() : 999;

                Long bundleId = existing != null ? existing.getId() : notificationMapper.findIdByReceiverIdAndEventKey(request.getReceiverId(), eventKey);
                NotificationResponse bundleResponse = NotificationResponse.builder()
                        .id(bundleId)
                        .type(request.getType())
                        .title(title)
                        .content(content)
                        .isRead(false)
                        .createdAt(existing != null ? existing.getCreatedAt() : updateNow)
                        .orderId(request.getOrderId())
                        .chatRoomId(request.getChatRoomId())
                        .commentId(request.getCommentId())
                        .postId(request.getPostId())
                        .reviewId(request.getReviewId())
                        .userCouponId(request.getUserCouponId())
                        .lastEventAt(updateNow)
                        .build();
                registerWebSocketSending(request.getReceiverId(), bundleResponse);

                if (minutesGap >= 30 && scope == DeliveryScope.WEB_AND_SMS && request.getReceiverId() != null) {
                    String receiverPhone = notificationMapper.findReceiverPhone(request.getReceiverId(), request.getOrderId());
                    registerSmsSending(bundleId, receiverPhone, title, content, Integer.MAX_VALUE);
                }
            }
            return;
        }

        // 1. 알림 실시간 웹소켓(STOMP) 전파를 먼저 등록하여 SMS 예약 실패와 독립적으로 전파 보장
        NotificationResponse responseDTO = NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getNotificationType())
                .title(title)
                .content(content)
                .isRead(false)
                .createdAt(notification.getCreatedAt())
                .orderId(notification.getOrderId())
                .chatRoomId(notification.getChatRoomId())
                .commentId(notification.getCommentId())
                .postId(notification.getPostId())
                .reviewId(notification.getReviewId())
                .userCouponId(notification.getUserCouponId())
                .lastEventAt(notification.getLastEventAt())
                .build();
        registerWebSocketSending(request.getReceiverId(), responseDTO);

        // 2. 알림톡 / SMS 외부 발송 연동 (DB 트랜잭션 커밋 완료 후 안전하게 발송)
        if (scope == DeliveryScope.WEB_AND_SMS && request.getReceiverId() != null) {
            String receiverPhone = notificationMapper.findReceiverPhone(request.getReceiverId(), request.getOrderId());
            registerSmsSending(notification.getId(), receiverPhone, title, content);
        }

        // 3. 주문제작 반려 시 반려 사유 채팅 메시지 자동 발송 이벤트 발행 (AFTER_COMMIT 후 ChatEventListener 처리)
        if (NotificationType.CUSTOM_ORDER_REJECTED.equals(request.getType())
                && request.getOrderId() != null
                && request.getReceiverId() != null) {
            eventPublisher.publishEvent(
                    new CustomOrderRejectedChatEvent(request.getOrderId(), request.getReceiverId()));
        }
    }

    /**
     * 실패한 외부 SMS 발송에 대한 재시도를 수행한다. (최대 2회 상한 보존)
     */
    public void retrySmsForNotification(Long notificationId, Long receiverId, String title, String content) {
        if (notificationId == null || receiverId == null) return;
        String receiverPhone = notificationMapper.findReceiverPhone(receiverId, null);
        registerSmsSending(notificationId, receiverPhone, title, content, 2);
    }

    private void registerSmsSending(Long notificationId, String receiverPhone, String title, String content) {
        registerSmsSending(notificationId, receiverPhone, title, content, 2);
    }

    private void registerSmsSending(Long notificationId, String receiverPhone, String title, String content, int maxAttempts) {
        if (notificationId == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executeSmsSending(notificationId, receiverPhone, title, content, maxAttempts);
                }
            });
        } else {
            executeSmsSending(notificationId, receiverPhone, title, content, maxAttempts);
        }
    }

    private void executeSmsSending(Long notificationId, String receiverPhone, String title, String content, int maxAttempts) {
        try {
            if (receiverPhone == null || receiverPhone.trim().isEmpty()) {
                Long deliveryId = notificationDeliveryService.reserveDeliveryAttempt(notificationId, "NO_PHONE", maxAttempts);
                if (deliveryId != null) {
                    notificationDeliveryService.updateDeliveryResult(deliveryId, "FAILED", null, "No receiver phone number", null);
                }
                return;
            }

            // 외부 SMS 발송 전 시도 횟수를 원자적으로 예약(PENDING)하여 중복 발송 및 한도 초과 방지
            Long deliveryId = notificationDeliveryService.reserveDeliveryAttempt(notificationId, receiverPhone, maxAttempts);
            if (deliveryId == null) {
                // 이미 SENT/DELIVERED 성공했거나 최대 시도 횟수에 도달한 경우
                return;
            }

            try {
                SolapiKakaoAlimtalkClient.SmsResult result = solapiKakaoAlimtalkClient.sendAlimtalk(notificationId, receiverPhone, title, content);
                if (result != null) {
                    LocalDateTime sentAt = "SENT".equals(result.getStatus()) ? LocalDateTime.now(clock) : null;
                    notificationDeliveryService.updateDeliveryResult(
                            deliveryId,
                            result.getStatus(),
                            result.getProviderMessageId(),
                            result.getFailureReason(),
                            sentAt
                    );
                } else {
                    notificationDeliveryService.updateDeliveryResult(
                            deliveryId,
                            "FAILED",
                            null,
                            "Null response from SMS provider",
                            null
                    );
                }
            } catch (Exception e) {
                notificationDeliveryService.updateDeliveryResult(
                        deliveryId,
                        "FAILED",
                        null,
                        "Exception during SMS sending: " + e.getMessage(),
                        null
                );
            }
        } catch (Exception e) {
            log.warn("SMS 발송 예약/실행 중 예외 발생 (notificationId={}): {}", notificationId, e.getMessage());
        }
    }

    private void registerWebSocketSending(Long receiverId, NotificationResponse responseDTO) {
        if (responseDTO == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executeWebSocketSending(receiverId, responseDTO);
                }
            });
        } else {
            executeWebSocketSending(receiverId, responseDTO);
        }
    }

    private void executeWebSocketSending(Long receiverId, NotificationResponse responseDTO) {
        try {
            if (receiverId != null && memberNotificationQueryService.isMemberActive(receiverId)) {
                messagingTemplate.convertAndSend("/topic/notifications/" + receiverId, responseDTO);
            }
        } catch (Exception e) {
            // 웹소켓 전파 예외는 메인 발송 로직에 영향을 주지 않는다.
        }
    }

    // 특정 회원 알림 목록 최신순 페이징 조회
    @Transactional(readOnly = true)
    public List<NotificationResponse> findUserNotificationList(Long receiverId, int size, int offset) {
        return notificationMapper.findUserNotificationList(receiverId, size, offset);
    }

    // 알림 1개 읽음 처리
    @Transactional
    public void markAsRead(Long id, Long receiverId) {
        notificationMapper.markAsRead(id, receiverId);
    }

    // 알림 전체 읽음 처리
    @Transactional
    public void markAllAsRead(Long receiverId) {
        notificationMapper.markAllAsRead(receiverId);
    }

    // 종 모양 옆에 띄울 안 읽은 알림 개수 조회
    @Transactional(readOnly = true)
    public int countUnreadNotifications(Long receiverId) {
        return notificationMapper.countUnreadNotifications(receiverId);
    }
}
