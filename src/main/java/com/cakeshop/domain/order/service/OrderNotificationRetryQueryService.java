package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.mapper.OrderMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작성자: 민정
 * 담당자: 주환
 * 작성일: 2026-11-16
 * 기능: 주문 알림 재시도 가능 여부 조회
 * 설명: 현재 주문 상태와 픽업 일정으로 기존 알림의 유효 여부를 판단한다.
 */
@Service
@RequiredArgsConstructor
public class OrderNotificationRetryQueryService {

    private final OrderMapper orderMapper;

    @Transactional(readOnly = true)
    public boolean isCurrent(long orderId, String type, LocalDateTime createdAt, LocalDateTime now) {
        var order = orderMapper.findOrderById(orderId).orElse(null);
        if (order == null || order.getStatus() == null) {
            return false;
        }

        var status = order.getStatus();
        return switch (type) {
            case "ORDER_PAID", "NEW_ORDER" -> status == OrderStatus.READY_FOR_PICKUP;
            case "CUSTOM_ORDER_PAID", "NEW_CUSTOM_ORDER" -> status == OrderStatus.UNDER_REVIEW;
            case "CUSTOM_ORDER_IN_PRODUCTION" -> status == OrderStatus.IN_PRODUCTION;
            case "CUSTOM_ORDER_REJECTED" -> status == OrderStatus.REJECTED;
            case "ORDER_CANCELED", "ORDER_CANCEL_REQUEST" -> status == OrderStatus.CANCELED;
            case "CUSTOMER_ORDER_PICKED_UP", "ADMIN_PICKEDUP" -> status == OrderStatus.PICKED_UP;
            case "CUSTOMER_PICKUP_REMINDER_TOMORROW", "ADMIN_PICKUP_REMINDER_TOMORROW" ->
                    isUpcomingPickup(status, order.getPickupAt(), order.getUpdatedAt(), createdAt, now, 1);
            case "CUSTOMER_PICKUP_REMINDER_TODAY", "ADMIN_PICKUP_REMINDER_TODAY" ->
                    isUpcomingPickup(status, order.getPickupAt(), order.getUpdatedAt(), createdAt, now, 0);
            default -> false;
        };
    }

    private boolean isUpcomingPickup(
            OrderStatus status, LocalDateTime pickupAt, LocalDateTime updatedAt,
            LocalDateTime createdAt, LocalDateTime now, int dayOffset) {
        return (status == OrderStatus.READY_FOR_PICKUP || status == OrderStatus.IN_PRODUCTION
                || status == OrderStatus.UNDER_REVIEW)
                // 알림에 이전 예약 시각이 없어 이후 수정이 있었으면 기존 안내를 다시 보내지 않는다.
                && (updatedAt == null || !updatedAt.isAfter(createdAt))
                && pickupAt != null
                && createdAt.toLocalDate().equals(now.toLocalDate())
                && pickupAt.toLocalDate().equals(now.toLocalDate().plusDays(dayOffset))
                && pickupAt.isAfter(now);
    }
}
