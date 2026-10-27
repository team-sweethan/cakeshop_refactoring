package com.cakeshop.domain.order.service.payment;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 주환
 * 담당자 : 주환
 * 작성일 : 2026-08-11
 * 기능 : 결제 환불 연동용 주문 취소 명령 계약
 * 설명 : 결제 도메인이 orders 테이블을 직접 변경하지 않고 주문 취소·수제 반려·재고·쿠폰 복구를 요청하도록 제공한다.
 * ******************************
 */
@Service
@RequiredArgsConstructor
public class OrderPaymentCancellationCommandService {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ADMIN = "ADMIN";
    public static final String ADMIN_REJECTION = "ADMIN_REJECTION";

    private final OrderMapper orderMapper;
    private final ProductStockService productStockService;
    private final CouponOrderCommandService couponOrderCommandService;

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 고객 소유 결제 취소 대상 주문 잠금
     * 설명 : 고객 환불 준비 시 주문 소유권을 검증하고 주문 행을 잠근다.
     * ******************************
     */
    @Transactional
    public void lockCustomerOrderForPaymentCancellation(long memberId, long orderId) {
        Order order = findOrderForUpdate(orderId);
        if (!Long.valueOf(memberId).equals(order.getMemberId())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 관리자 결제 취소 대상 주문 잠금
     * 설명 : 관리자 환불 준비 시 주문 행을 잠근다.
     * ******************************
     */
    @Transactional
    public void lockOrderForPaymentCancellation(long orderId) {
        findOrderForUpdate(orderId);
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 결제 취소 가능 여부 확인
     * 설명 : 잠금 대기 전 스냅샷 대신 현재 주문을 잠금 조회해 요청 유형별 취소 가능 상태를 검증한다.
     * ******************************
     */
    @Transactional
    public boolean isPaymentCancellationAvailable(
            long orderId,
            String requestType,
            LocalDateTime requestedAt
    ) {
        return orderMapper.findOrderByIdForUpdate(orderId)
                .map(order -> isPaymentCancellationAvailable(order, requestType, requestedAt))
                .orElse(false);
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 결제 취소 완료 여부 확인
     * 설명 : PG 취소 재시도 시 요청 유형에 맞는 주문 최종 상태가 이미 반영됐는지 확인한다.
     * ******************************
     */
    @Transactional(readOnly = true)
    public boolean isPaymentCancellationCompleted(long orderId, String requestType) {
        return orderMapper.findOrderById(orderId)
                .map(order -> isPaymentCancellationCompleted(order, requestType))
                .orElse(false);
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 결제 취소 완료 반영
     * 설명 : PG 취소 성공 뒤 고객 취소는 CANCELED, 관리자 수제 반려는 REJECTED로 확정하고 복구를 반영한다.
     * ******************************
     */
    @Transactional
    public boolean completePaymentCancellation(
            long orderId,
            String requestType,
            long requestedBy,
            String reason,
            LocalDateTime requestedAt,
            LocalDateTime canceledAt
    ) {
        Order order = findOrderForUpdate(orderId);
        return completePaymentCancellation(
                order,
                orderId,
                requestType,
                requestedBy,
                reason,
                requestedAt,
                canceledAt
        );
    }

    private boolean completePaymentCancellation(
            Order order,
            long orderId,
            String requestType,
            long requestedBy,
            String reason,
            LocalDateTime requestedAt,
            LocalDateTime canceledAt
    ) {
        if (isPaymentCancellationCompleted(order, requestType)) {
            restoreCancellationSideEffects(orderId, canceledAt);
            return true;
        }
        if (requestedBy <= 0
                || reason == null
                || reason.isBlank()
                || !isPaymentCancellationAvailable(order, requestType, requestedAt)) {
            return false;
        }

        int affectedRows = switch (requestType) {
            case CUSTOMER -> order.getOrderType() == OrderType.GENERAL
                    ? orderMapper.cancelIfCurrent(
                            orderId,
                            OrderStatus.READY_FOR_PICKUP,
                            CUSTOMER,
                            reason,
                            requestedAt,
                            canceledAt
                    )
                    : orderMapper.cancelCustomIfUnderReview(
                            orderId,
                            CUSTOMER,
                            reason,
                            canceledAt
                    );
            case ADMIN -> orderMapper.cancelIfCurrent(
                    orderId,
                    OrderStatus.READY_FOR_PICKUP,
                    ADMIN,
                    reason,
                    requestedAt,
                    canceledAt
            );
            case ADMIN_REJECTION -> orderMapper.rejectIfUnderReview(
                    orderId,
                    requestedBy,
                    canceledAt,
                    reason
            );
            default -> 0;
        };
        if (affectedRows != 1) {
            return false;
        }
        restoreCancellationSideEffects(orderId, canceledAt);
        return true;
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 일반 주문 결제 취소 가능 여부 확인
     * 설명 : 기존 일반 주문 취소 계약과의 호환성을 유지한다.
     * ******************************
     */
    @Transactional
    public boolean isGeneralPaymentCancellationAvailable(long orderId, LocalDateTime requestedAt) {
        return isPaymentCancellationAvailable(orderId, CUSTOMER, requestedAt);
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 일반 주문 결제 취소 완료 여부 확인
     * 설명 : 기존 일반 주문 취소 계약과의 호환성을 유지한다.
     * ******************************
     */
    @Transactional(readOnly = true)
    public boolean isGeneralPaymentCancellationCompleted(long orderId) {
        return orderMapper.findOrderById(orderId)
                .map(order -> order.getOrderType() == OrderType.GENERAL
                        && order.getStatus() == OrderStatus.CANCELED)
                .orElse(false);
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 일반 주문 결제 취소 완료 반영
     * 설명 : 기존 일반 주문 취소 계약과의 호환성을 유지한다.
     * ******************************
     */
    @Transactional
    public boolean completeGeneralPaymentCancellation(
            long orderId,
            String canceledBy,
            String reason,
            LocalDateTime requestedAt,
            LocalDateTime canceledAt
    ) {
        Order order = findOrderForUpdate(orderId);
        if (order.getOrderType() != OrderType.GENERAL) {
            return false;
        }
        return completePaymentCancellation(
                order,
                orderId,
                canceledBy,
                1L,
                reason,
                requestedAt,
                canceledAt
        );
    }

    private Order findOrderForUpdate(long orderId) {
        return orderMapper.findOrderByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    private boolean isPaymentCancellationAvailable(
            Order order,
            String requestType,
            LocalDateTime requestedAt
    ) {
        return switch (requestType) {
            case CUSTOMER -> isGeneralPaymentCancellationAvailable(order, requestedAt)
                    || isCustomUnderReview(order);
            case ADMIN -> isGeneralPaymentCancellationAvailable(order, requestedAt);
            case ADMIN_REJECTION -> isCustomUnderReview(order);
            default -> false;
        };
    }

    private boolean isPaymentCancellationCompleted(Order order, String requestType) {
        return switch (requestType) {
            case CUSTOMER -> order.getStatus() == OrderStatus.CANCELED
                    && (order.getOrderType() == OrderType.GENERAL
                    || order.getOrderType() == OrderType.CUSTOM);
            case ADMIN -> order.getOrderType() == OrderType.GENERAL
                    && order.getStatus() == OrderStatus.CANCELED;
            case ADMIN_REJECTION -> order.getOrderType() == OrderType.CUSTOM
                    && order.getStatus() == OrderStatus.REJECTED;
            default -> false;
        };
    }

    private boolean isGeneralPaymentCancellationAvailable(Order order, LocalDateTime requestedAt) {
        return order.getOrderType() == OrderType.GENERAL
                && order.getStatus() == OrderStatus.READY_FOR_PICKUP
                && requestedAt != null
                && order.getPickupAt() != null
                && requestedAt.isBefore(order.getPickupAt());
    }

    private boolean isCustomUnderReview(Order order) {
        return order.getOrderType() == OrderType.CUSTOM
                && order.getStatus() == OrderStatus.UNDER_REVIEW;
    }

    private void restoreCancellationSideEffects(long orderId, LocalDateTime restoredAt) {
        couponOrderCommandService.restoreCouponForCanceledOrder(orderId);
        restoreDeductedStock(orderId, restoredAt);
    }

    private void restoreDeductedStock(long orderId, LocalDateTime restoredAt) {
        List<OrderItem> items = orderMapper.findStockDeductedItemsForRestore(orderId);
        for (OrderItem item : items) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
            }
            productStockService.restoreStock(item.getProductId(), item.getQuantity());
            if (orderMapper.markStockRestoredIfDeducted(item.getId(), restoredAt) != 1) {
                throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
            }
        }
    }
}
