package com.cakeshop.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cakeshop.domain.member.service.MemberNotificationQueryService;
import com.cakeshop.domain.notification.dto.form.NotificationRequest;
import com.cakeshop.domain.notification.entity.Notification;
import com.cakeshop.domain.notification.entity.NotificationDelivery;
import com.cakeshop.domain.notification.entity.NotificationType;
import com.cakeshop.domain.notification.mapper.NotificationMapper;
import com.cakeshop.global.infra.kakao.SolapiKakaoAlimtalkClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class NotificationTimeTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T15:05:00Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime expected = LocalDateTime.parse("2026-10-29T00:05:00");

    @Test
    void notificationAndLastEventUseSeoulTime() {
        var mapper = mock(NotificationMapper.class);
        var service = new NotificationService(mapper, clock, mock(MemberNotificationQueryService.class),
                mock(NotificationDeliveryService.class), mock(SolapiKakaoAlimtalkClient.class),
                mock(SimpMessagingTemplate.class), mock(ApplicationEventPublisher.class));
        service.makeNotification(NotificationRequest.builder().receiverId(1L).type(NotificationType.ORDER_PAID)
                .eventKey("timezone-test").build());
        var saved = ArgumentCaptor.forClass(Notification.class);
        verify(mapper).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(expected);
        assertThat(saved.getValue().getLastEventAt()).isEqualTo(expected);
    }

    @Test
    void deliveryReservationUsesSameSeoulTimeAsDatabaseCooldownComparisons() {
        var mapper = mock(NotificationMapper.class);
        var service = new NotificationDeliveryService(mapper, clock);
        service.reserveDeliveryAttempt(1L, "test-recipient", 2);
        var saved = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(mapper).saveDelivery(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(expected);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(expected);
    }
}
