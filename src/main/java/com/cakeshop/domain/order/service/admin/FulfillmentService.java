package com.cakeshop.domain.order.service.admin;

import com.cakeshop.domain.order.dto.form.admin.FulfillmentSearchCondition;
import com.cakeshop.domain.order.dto.form.admin.FulfillmentSort;
import com.cakeshop.domain.order.dto.view.admin.FulfillmentStatusCount;
import com.cakeshop.domain.order.dto.view.admin.FulfillmentListView;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.payment.service.PaymentOrderQueryService;
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
import java.util.Optional;
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
    // 결제 도메인 공개 계약: PG(돈워리) 결제 확인 중 여부 (conventions 12절)
    private final PaymentOrderQueryService paymentOrderQueryService;

    /**
     * 검토·제작·픽업 업무를 페이지 단위로 조회하고 상품·옵션 스냅샷을 조합한다.
     * 단계를 고르지 않으면 처리할 주문(검토 · 제작 · 픽업 대기)만 보이고, 정렬은 그 단계에서 고를 수 있는 값만 쓴다.
     */
    @Transactional(readOnly = true)
    public FulfillmentListView getFulfillments(
            FulfillmentSearchCondition condition,
            PageRequest pageRequest
    ) {
        OrderStatus selectedStatus = normalizeStatus(
                condition == null ? null : condition.getStatus()
        );
        FulfillmentSort selectedSort = FulfillmentSort.resolve(
                condition == null ? null : condition.getSort(),
                selectedStatus
        );
        PageRequest normalizedPageRequest = pageRequest == null
                ? new PageRequest(null, null)
                : pageRequest;
        long totalOrders = orderMapper.countFulfillmentOrders(selectedStatus);
        List<FulfillmentListView.FulfillmentOrder> orders = totalOrders <= normalizedPageRequest.getOffset()
                ? List.of()
                : orderMapper.findFulfillmentOrders(
                                selectedStatus,
                                selectedSort,
                                normalizedPageRequest.getSize(),
                                normalizedPageRequest.getOffset()
                        )
                        .stream()
                        .map(this::toFulfillmentOrder)
                        .toList();

        Map<OrderStatus, Long> statusCounts = orderMapper.countFulfillmentOrdersByStatus()
                .stream()
                .collect(Collectors.toMap(FulfillmentStatusCount::status, FulfillmentStatusCount::count));

        return new FulfillmentListView(
                selectedStatus,
                new PageResult<>(orders, normalizedPageRequest, totalOrders),
                selectedSort,
                statusCounts
        );
    }

    /**
     * 주문 상세 화면에 주문 처리 버튼을 보이기 위해 주문 하나의 처리 정보(가능한 다음 단계 · 결제 확인 중)를 조회한다.
     * 주문 처리 목록과 같은 규칙(toFulfillmentOrder)으로 계산하고, 처리 단계가 아닌 주문(결제 대기 · 취소 · 반려 · 만료)은 비어 있다.
     */
    @Transactional(readOnly = true)
    public Optional<FulfillmentListView.FulfillmentOrder> getFulfillmentOrder(long orderId) {
        if (orderId <= 0) {
            return Optional.empty();
        }
        return orderMapper.findOrderById(orderId)
                .filter(order -> FULFILLMENT_STATUSES.contains(order.getStatus()))
                .map(this::toFulfillmentOrder);
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

    /** PG(돈워리)가 주문의 결제를 아직 확인 중인지(판정 전) 조회한다 (DonWorry ADR-0064 4절). */
    @Transactional(readOnly = true)
    public boolean isPaymentUnderGatewayReview(long orderId) {
        return paymentOrderQueryService.isUnderGatewayReview(orderId);
    }

    /** DONE 결제가 유지되는 픽업 준비 주문만 수령 완료로 원자적으로 변경한다. */
    @Transactional
    public void markPickedUp(long orderId, long adminMemberId) {
        markPickedUp(orderId, adminMemberId, false);
    }

    /**
     * 픽업 준비 주문을 수령 완료로 변경한다. PG가 결제를 확인 중이면 확인 창 동의(reviewAcknowledged)가 있어야 하고
     * 처음 진행한 관리자와 시각을 남긴다(DonWorry ADR-0064 4절).
     */
    @Transactional
    public void markPickedUp(long orderId, long adminMemberId, boolean reviewAcknowledged) {
        if (orderId <= 0 || adminMemberId <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        requireReviewAcknowledged(orderId, adminMemberId, reviewAcknowledged, now);
        // 상태가 READY_FOR_PICKUP 인지
        int affectedRows = orderMapper.markPickedUpIfReady(
                orderId,
                adminMemberId,
                now
        );
        if (affectedRows != 1) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    /**
     * ******************************
     * 작성자 : 수민
     * 담당자 : 주환
     * 작성일 : 2026-11-04
     * 기능 : PG 결제 확인 중인 일반 주문을 관리자가 픽업 준비로 넘긴다
     * 설명 : 고객에게 확인해 판정 전에 진행할 때 쓴다. 결제가 아직 확인 중이면 확인 창 동의가 있어야 하고
     *        진행 기록을 남긴다. 판정이 승인으로 끝나면 시스템이 자동으로 넘기므로 이 동작이 필요 없다.
     * ******************************
     */
    @Transactional
    public void moveToReadyForPickup(long orderId, long adminMemberId, boolean reviewAcknowledged) {
        if (orderId <= 0 || adminMemberId <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        requireReviewAcknowledged(orderId, adminMemberId, reviewAcknowledged, now);
        if (orderMapper.markGeneralReadyForPickupIfUnderReview(orderId, now) != 1) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    // PG가 결제를 확인 중이면 확인 창 동의가 있어야 진행한다. 동의했으면 처음 진행한 관리자·시각을 남긴다.
    private void requireReviewAcknowledged(
            long orderId,
            long adminMemberId,
            boolean reviewAcknowledged,
            LocalDateTime proceededAt
    ) {
        if (!paymentOrderQueryService.isUnderGatewayReview(orderId)) {
            return;
        }
        if (!reviewAcknowledged) {
            throw new BusinessException(OrderErrorCode.PAYMENT_UNDER_REVIEW_CONFIRM_REQUIRED);
        }
        orderMapper.recordGatewayReviewProceed(orderId, adminMemberId, proceededAt);
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
        boolean paymentUnderReview = order.getStatus() != OrderStatus.PICKED_UP
                && paymentOrderQueryService.isUnderGatewayReview(order.getId());
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
                isReadyForPickupMovable(order, requestedRefund),
                paymentUnderReview,
                items
        );
    }

    // 일반 주문의 UNDER_REVIEW는 PG가 결제를 확인 중인 주문이다. 관리자가 고객 확인 뒤 픽업 준비로 넘길 수 있다.
    private boolean isReadyForPickupMovable(Order order, boolean requestedRefund) {
        return order.getOrderType() == com.cakeshop.domain.order.entity.OrderType.GENERAL
                && order.getStatus() == OrderStatus.UNDER_REVIEW
                && !requestedRefund;
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
