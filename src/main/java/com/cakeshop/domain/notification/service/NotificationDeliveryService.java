package com.cakeshop.domain.notification.service;

import java.time.LocalDateTime;
import java.time.Clock;
import com.cakeshop.domain.notification.entity.NotificationDelivery;
import com.cakeshop.domain.notification.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발송 이력 독립 트랜잭션 전용 서비스 (REQUIRES_NEW)
 */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private final NotificationMapper notificationMapper;
    private final Clock clock;

    /**
     * 외부 SMS 전송 전, 시도 횟수를 원자적으로 예약(PENDING 상태로 사전 저장)한다.
     * 이미 성공(SENT)했거나 최대 시도 횟수에 도달한 경우 null을 반환한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long reserveDeliveryAttempt(Long notificationId, String recipient, int maxAttempts) {
        if (notificationId == null) return null;
        try {
            // 동일 알림에 대한 발송 시도 검사 및 삽입을 비관적 락(FOR UPDATE)으로 완벽하게 직렬화
            notificationMapper.findNotificationByIdForUpdate(notificationId);

            // 단건 이벤트(주문 등)는 이미 성공(SENT/DELIVERED) 이력이 있으면 중복 발송 차단.
            // 단, 30분 쿨다운마다 새 문자가 지속 발송되어야 하는 채팅 묶음 알림(maxAttempts == Integer.MAX_VALUE)은 성공 단축 검사에서 제외.
            if (maxAttempts < Integer.MAX_VALUE && notificationMapper.hasSentDelivery(notificationId)) {
                return null;
            }

            // 현재 외부 전송이 진행 중인(최근 1분 이내 생성된) 활성 PENDING 건이 있으면 동시 중복 발송 차단
            if (notificationMapper.hasActivePendingDelivery(notificationId)) {
                return null;
            }

            if (notificationMapper.countDeliveryAttempts(notificationId) >= maxAttempts) {
                return null;
            }

            LocalDateTime now = LocalDateTime.now(clock);
            NotificationDelivery delivery = NotificationDelivery.builder()
                    .notificationId(notificationId)
                    .recipient(recipient)
                    .templateCode("DEFAULT_SMS")
                    .status("PENDING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            notificationMapper.saveDelivery(delivery);
            return delivery.getId();
        } catch (Exception e) {
            log.error("SMS 발송 시도 사전 예약(PENDING) 실패 (notificationId: {})", notificationId, e);
            throw new IllegalStateException("SMS 발송 시도 예약 실패: notificationId=" + notificationId, e);
        }
    }

    /**
     * 외부 SMS 전송 결과를 업데이트한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateDeliveryResult(Long deliveryId, String status, String providerMessageId, String failureReason, LocalDateTime sentAt) {
        if (deliveryId == null) return;
        try {
            notificationMapper.updateDeliveryResult(deliveryId, status, providerMessageId, failureReason, sentAt);
        } catch (Exception e) {
            log.error("SMS 발송 결과 업데이트 실패 (deliveryId: {})", deliveryId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDelivery(NotificationDelivery delivery) {
        try {
            notificationMapper.saveDelivery(delivery);
        } catch (Exception e) {
            log.error("notification_deliveries DB 저장 실패 (notificationId: {})", delivery != null ? delivery.getNotificationId() : null);
            throw e;
        }
    }

    /**
     * 회원 탈퇴, 쿠폰 사용/비활성화/만료 연장 등으로 더 이상 재발송이 불필요한 알림을 SKIPPED 상태로 종결 기록한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSkippedAttempt(Long notificationId, String reason) {
        if (notificationId == null) return;
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            NotificationDelivery delivery = NotificationDelivery.builder()
                    .notificationId(notificationId)
                    .recipient("SKIPPED")
                    .templateCode("DEFAULT_SMS")
                    .status("SKIPPED")
                    .failureReason(reason)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            notificationMapper.saveDelivery(delivery);
        } catch (Exception e) {
            log.error("재시도 스킵(SKIPPED) 종결 기록 실패 (notificationId: {})", notificationId, e);
        }
    }

    /**
     * 서버 비정상 종료 등으로 방치된 오래된 PENDING 발송 이력을 FAILED로 안전하게 복구 확정한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStalePendingDeliveries(int thresholdMinutes) {
        try {
            return notificationMapper.updateStalePendingDeliveriesToFailed(thresholdMinutes);
        } catch (Exception e) {
            log.error("오래된 PENDING 발송 이력 복구 처리 중 예외 발생:", e);
            return 0;
        }
    }
}
