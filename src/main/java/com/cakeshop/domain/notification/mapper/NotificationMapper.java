package com.cakeshop.domain.notification.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.cakeshop.domain.notification.dto.view.NotificationResponse;
import com.cakeshop.domain.notification.entity.Notification;
import com.cakeshop.domain.notification.entity.NotificationDelivery;

@Mapper
public interface NotificationMapper {

    // 알림 DB 저장하기
    void save(Notification notification);

    // 단일 알림 조회하기 (상세 조회용)
    Notification findById(@Param("id") Long id);

    // 알림 단건 읽음 처리
    void markAsRead(@Param("id") Long id, @Param("receiverId") Long receiverId);

    // 알림 전체 읽음 처리
    void markAllAsRead(@Param("receiverId") Long receiverId);

    // 특정 회원 알림 목록 최신순 페이징 조회
    List<NotificationResponse> findUserNotificationList(@Param("receiverId") Long receiverId, @Param("size") int size, @Param("offset") int offset);

    // 미확인 알림 개수 조회
    int countUnreadNotifications(@Param("receiverId") Long receiverId);

    // 중복 event_key 존재 여부
    boolean existsByReceiverIdAndEventKey(@Param("receiverId") Long receiverId, @Param("eventKey") String eventKey);

    // 중복 event_key 기반 기존 알림 객체 조회
    Notification findNotificationByReceiverAndEventKey(@Param("receiverId") Long receiverId, @Param("eventKey") String eventKey);

    // 중복 event_key 기반 기존 알림 비관적 잠금 조회 (FOR UPDATE - 동시성 중복 발송 방어)
    Notification findNotificationByReceiverAndEventKeyForUpdate(@Param("receiverId") Long receiverId, @Param("eventKey") String eventKey);

    // 중복 event_key 기반 최근 시각 갱신 및 unread 처리
    void updateLastEventAtAndUnread(@Param("receiverId") Long receiverId,
                                    @Param("eventKey") String eventKey,
                                    @Param("title") String title,
                                    @Param("content") String content,
                                    @Param("lastEventAt") LocalDateTime lastEventAt);

    // 중복 event_key 기반 기존 알림 ID 조회
    Long findIdByReceiverIdAndEventKey(@Param("receiverId") Long receiverId, @Param("eventKey") String eventKey);

    // 알림 ID 기반 비관적 잠금 조회 (FOR UPDATE - 발송 시도 원자적 예약 직렬화용)
    Long findNotificationByIdForUpdate(@Param("id") Long id);

    // 이미 SENT 성공 발송 이력이 있는지 확인
    boolean hasSentDelivery(@Param("notificationId") Long notificationId);

    // 현재 진행 중인(최근 1분 이내) PENDING 예약이 존재하는지 확인 (동시 발송 레이스 차단)
    boolean hasActivePendingDelivery(@Param("notificationId") Long notificationId);

    // 이미 발송 시도 이력(SENT, DELIVERED, FAILED, SKIPPED)이 한 번이라도 존재하는지 확인
    boolean hasAttemptedDelivery(@Param("notificationId") Long notificationId);

    // 발송 시도 총 횟수 조회 (최대 재시도 횟수 제한용)
    int countDeliveryAttempts(@Param("notificationId") Long notificationId);

    // 최근 생성된 쿠폰 알림의 생성 시각 조회 (서버 재기동 시 영속 체크포인트 복구용)
    LocalDateTime findLatestCouponNotificationCreatedAt();

    // 실패한 쿠폰 알림 중 재시도(2회 미만) 가능한 알림 목록 조회 (커서 페이징 지원)
    List<Notification> findRetryableCouponNotifications(@Param("lastId") Long lastId, @Param("limit") int limit);

    List<Notification> findRetryableOrderNotifications(@Param("limit") int limit, @Param("since") LocalDateTime since);

    // 수신 회원 전화번호 조회 (주문서 작성 번호 우선, 알림톡 발송용)
    String findReceiverPhone(@Param("receiverId") Long receiverId, @Param("orderId") Long orderId); 

    // 문자로도 알림 보내기
    void saveDelivery(NotificationDelivery delivery);

    // 발송 결과 업데이트 (상태, 프로바이더 메시지 ID, 실패 사유, 발송 시각)
    void updateDeliveryResult(@Param("deliveryId") Long deliveryId,
                              @Param("status") String status,
                              @Param("providerMessageId") String providerMessageId,
                              @Param("failureReason") String failureReason,
                              @Param("sentAt") LocalDateTime sentAt);

    // 프로세스 중단 등으로 방치된 오래된 PENDING 발송 이력을 FAILED로 안전하게 확정 종결 (복구 경로)
    int updateStalePendingDeliveriesToFailed(@Param("thresholdMinutes") int thresholdMinutes);
}
