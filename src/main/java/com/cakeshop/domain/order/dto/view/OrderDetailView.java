package com.cakeshop.domain.order.dto.view;

import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 주문 당시 저장한 정보만으로 구성한 주문 상세 화면 모델이다. */
public record OrderDetailView(
        long orderId,
        String orderNumber,
        OrderType orderType,
        OrderStatus status,
        String ordererName,
        String ordererPhone,
        String pickupName,
        String pickupPhone,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal finalAmount,
        LocalDateTime pickupAt,
        LocalDateTime paymentExpiresAt,
        boolean paymentPending,
        String requestMessage,
        LocalDateTime createdAt,
        boolean cancelRequestAvailable,
        List<Item> items
) {

    public String statusLabel() {
        return status.statusLabel();
    }

    // 일반 주문의 UNDER_REVIEW는 PG(돈워리)가 결제를 확인 중인 주문이라 주문 처리에서 픽업 준비로 넘길 수 있다.
    public boolean isFulfillmentManageable() {
        return status == OrderStatus.READY_FOR_PICKUP
                || status == OrderStatus.UNDER_REVIEW
                || (orderType == OrderType.CUSTOM && status == OrderStatus.IN_PRODUCTION);
    }

    public boolean isAdminCancellationAvailable() {
        return orderType == OrderType.GENERAL && cancelRequestAvailable();
    }

    public String customerCancellationGuide() {
        return orderType == OrderType.CUSTOM
                ? "주문 제작은 관리자 승인 전까지 전액 취소할 수 있습니다."
                : "일반 상품은 픽업 예정 시각 전까지 전액 취소할 수 있습니다.";
    }

    public record Item(
            String productName,
            int quantity,
            BigDecimal totalAmount,
            String requirements,
            List<Option> options
    ) {
    }

    public record Option(
            String groupName,
            String optionName
    ) {
    }
}
