package com.cakeshop.domain.order.service.admin;

import com.cakeshop.domain.order.dto.form.admin.FulfillmentSearchCondition;
import com.cakeshop.domain.order.dto.view.admin.FulfillmentListView;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.common.paging.PageResult;
import com.cakeshop.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 픽업 가능한 주문의 수령 완료 처리를 담당한다. */
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    public static final int PAGE_SIZE = 20;

    private static final Set<OrderStatus> FULFILLMENT_STATUSES = Set.of(
            OrderStatus.UNDER_REVIEW,
            OrderStatus.IN_PRODUCTION,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.PICKED_UP
    );

    private final OrderMapper orderMapper;
    private final Clock clock;

    /** 검토·제작·픽업 업무 전체를 페이지 단위로 조회하고 상품·옵션 스냅샷을 조합한다. */
    @Transactional(readOnly = true)
    public FulfillmentListView getFulfillments(
            FulfillmentSearchCondition condition,
            PageRequest pageRequest
    ) {
        OrderStatus selectedStatus = normalizeStatus(
                condition == null ? null : condition.getStatus()
        );
        PageRequest normalizedPageRequest = pageRequest == null
                ? new PageRequest(null, null)
                : pageRequest;
        long totalOrders = orderMapper.countFulfillmentOrders(selectedStatus);
        List<FulfillmentListView.FulfillmentOrder> orders = totalOrders <= normalizedPageRequest.getOffset()
                ? List.of()
                : orderMapper.findFulfillmentOrders(
                                selectedStatus,
                                normalizedPageRequest.getSize(),
                                normalizedPageRequest.getOffset()
                        )
                        .stream()
                        .map(this::toFulfillmentOrder)
                        .toList();

        return new FulfillmentListView(
                selectedStatus,
                new PageResult<>(orders, normalizedPageRequest, totalOrders)
        );
    }

    /** 주문 상세에서 해당 주문이 보이는 작업 단계 페이지를 계산한다. */
    @Transactional(readOnly = true)
    public int getFulfillmentPage(long orderId, OrderStatus status) {
        if (orderId <= 0 || normalizeStatus(status) == null) {
            return 1;
        }
        Integer page = orderMapper.findFulfillmentPage(orderId, status, PAGE_SIZE);
        return page == null ? 1 : page;
    }

    /** DONE 결제가 유지되는 픽업 준비 주문만 수령 완료로 원자적으로 변경한다. */
    @Transactional
    public void markPickedUp(long orderId, long adminMemberId) {
        if (orderId <= 0 || adminMemberId <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
        // 상태가 READY_FOR_PICKUP 인지
        int affectedRows = orderMapper.markPickedUpIfReady(
                orderId,
                adminMemberId,
                LocalDateTime.now(clock)
        );
        if (affectedRows != 1) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    private OrderStatus normalizeStatus(OrderStatus status) {
        return status != null && FULFILLMENT_STATUSES.contains(status)
                ? status
                : null;
    }

    private FulfillmentListView.FulfillmentOrder toFulfillmentOrder(Order order) {
        List<OrderItem> orderItems = orderMapper.findOrderItemsByOrderId(order.getId());
        Map<Long, List<OrderItemOption>> optionsByItem = orderMapper
                .findOrderItemOptionsByOrderId(order.getId())
                .stream()
                .collect(Collectors.groupingBy(OrderItemOption::getOrderItemId));

        List<FulfillmentListView.Item> items = orderItems.stream()
                .map(item -> new FulfillmentListView.Item(
                        item.getProductName(),
                        item.getQuantity(),
                        item.getRequirements(),
                        optionsByItem.getOrDefault(item.getId(), List.of())
                                .stream()
                                .map(option -> option.getOptionGroupName()
                                        + ": " + option.getOptionName())
                                .toList()
                ))
                .toList();

        boolean requestedRefund = orderMapper.hasRequestedRefundCancellation(order.getId());
        return new FulfillmentListView.FulfillmentOrder(
                order.getId(),
                order.getOrderNumber(),
                order.getOrderType(),
                order.getStatus(),
                order.getPickupName(),
                order.getPickupPhone(),
                order.getPickupAt(),
                order.getRequestMessage(),
                isProductionStartable(order, requestedRefund),
                isProductionCompletable(order, requestedRefund),
                isProductionStartable(order, requestedRefund),
                isPickupCompletable(order, requestedRefund),
                items
        );
    }

    private boolean isProductionStartable(Order order, boolean requestedRefund) {
        return order.getOrderType() == com.cakeshop.domain.order.entity.OrderType.CUSTOM
                && order.getStatus() == OrderStatus.UNDER_REVIEW
                && !requestedRefund;
    }

    private boolean isProductionCompletable(Order order, boolean requestedRefund) {
        return order.getOrderType() == com.cakeshop.domain.order.entity.OrderType.CUSTOM
                && order.getStatus() == OrderStatus.IN_PRODUCTION
                && !requestedRefund;
    }

    private boolean isPickupCompletable(Order order, boolean requestedRefund) {
        return order.getStatus() == OrderStatus.READY_FOR_PICKUP
                && order.getPickupAt() != null
                && !requestedRefund;
    }
}
