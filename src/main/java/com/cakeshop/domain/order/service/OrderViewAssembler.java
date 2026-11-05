package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.dto.view.OrderDetailView;
import com.cakeshop.domain.order.dto.view.OrderListView;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 고객·관리자 주문 조회가 공통으로 사용하는 주문 화면 데이터 조립을 담당한다. */
@Component
@RequiredArgsConstructor
public class OrderViewAssembler {

    private final OrderMapper orderMapper;
    private final Clock clock;

    public List<Order> findOrdersByMemberId(long memberId) {
        return orderMapper.findOrdersByMemberId(memberId);
    }

    public List<Order> findAllOrders() {
        return orderMapper.findAllOrders();
    }

    public Order findOrder(long orderId) {
        if (orderId <= 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return orderMapper.findOrderById(orderId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    public OrderListView toListView(Order order) {
        List<OrderItem> items = orderMapper.findOrderItemsByOrderId(order.getId());
        String productName = items.isEmpty() ? "주문 상품 없음" : items.getFirst().getProductName();
        return new OrderListView(
                order.getId(), order.getMemberId(), order.getOrderNumber(), order.getOrdererName(),
                order.getOrderType(), order.getStatus(), productName, items.size(), order.getFinalAmount(),
                order.getPickupAt(), order.getCreatedAt()
        );
    }

    public OrderDetailView toDetailView(Order order) {
        List<OrderItem> items = orderMapper.findOrderItemsByOrderId(order.getId());
        Map<Long, List<OrderItemOption>> optionsByItem = orderMapper.findOrderItemOptionsByOrderId(order.getId())
                .stream().collect(Collectors.groupingBy(OrderItemOption::getOrderItemId));
        List<OrderDetailView.Item> itemViews = items.stream()
                .map(item -> toItemView(item, optionsByItem))
                .toList();
        LocalDateTime now = LocalDateTime.now(clock);
        boolean cancellationRetryAvailable = orderMapper.hasRequestedRefundCancellation(order.getId());
        return new OrderDetailView(
                order.getId(), order.getOrderNumber(), order.getOrderType(), order.getStatus(),
                order.getOrdererName(), order.getOrdererPhone(), order.getPickupName(), order.getPickupPhone(),
                order.getOriginalAmount(), order.getDiscountAmount(), order.getFinalAmount(), order.getPickupAt(),
                order.getPaymentExpiresAt(), isPaymentPending(order, now), order.getRequestMessage(), order.getCreatedAt(),
                cancellationRetryAvailable || isCancellationRequestAvailable(order, now),
                itemViews
        );
    }

    private boolean isPaymentPending(Order order, LocalDateTime now) {
        return order.getStatus() == OrderStatus.PENDING_PAYMENT
                && order.getPaymentExpiresAt() != null
                && now.isBefore(order.getPaymentExpiresAt());
    }

    private boolean isCancellationRequestAvailable(Order order, LocalDateTime now) {
        if (order.getOrderType() == OrderType.CUSTOM) {
            return order.getStatus() == OrderStatus.UNDER_REVIEW;
        }
        // 일반 주문의 UNDER_REVIEW는 PG가 결제를 확인 중인 주문이다. 픽업 준비와 같은 규칙으로 취소할 수 있다.
        return order.getOrderType() == OrderType.GENERAL
                && (order.getStatus() == OrderStatus.READY_FOR_PICKUP
                || order.getStatus() == OrderStatus.UNDER_REVIEW)
                && order.getPickupAt() != null
                && now.isBefore(order.getPickupAt());
    }

    private OrderDetailView.Item toItemView(
            OrderItem item,
            Map<Long, List<OrderItemOption>> optionsByItem
    ) {
        List<OrderDetailView.Option> options = optionsByItem.getOrDefault(item.getId(), List.of()).stream()
                .map(option -> new OrderDetailView.Option(option.getOptionGroupName(), option.getOptionName()))
                .toList();
        return new OrderDetailView.Item(
                item.getProductName(), item.getQuantity(), item.getTotalAmount(), item.getRequirements(), options
        );
    }
}
