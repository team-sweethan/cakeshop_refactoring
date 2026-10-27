package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.payment.OrderPaymentCancellationCommandService;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderPaymentCancellationCommandServiceTests {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 8, 2, 15, 0);
    private static final LocalDateTime CANCELED_AT = REQUESTED_AT.plusSeconds(2);

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ProductStockService productStockService;

    @Mock
    private CouponOrderCommandService couponOrderCommandService;

    private OrderPaymentCancellationCommandService service;

    @BeforeEach
    void setUp() {
        service = new OrderPaymentCancellationCommandService(
                orderMapper,
                productStockService,
                couponOrderCommandService
        );
    }

    @Test
    void lockCustomerOrderForPaymentCancellation_otherMember_returnsNotFound() {
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order(99L)));

        assertThatThrownBy(() -> service.lockCustomerOrderForPaymentCancellation(3L, 10L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND)
                );
    }

    @Test
    void isGeneralPaymentCancellationAvailable_readyOrderBeforePickup_returnsTrue() {
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order(3L)));

        assertThat(service.isGeneralPaymentCancellationAvailable(10L, REQUESTED_AT)).isTrue();
    }

    @Test
    void isPaymentCancellationAvailable_underReviewCustomOrder_allowsCustomerAndAdminRejection() {
        Order order = order(3L);
        order.setOrderType(OrderType.CUSTOM);
        order.setStatus(OrderStatus.UNDER_REVIEW);
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order));

        assertThat(service.isPaymentCancellationAvailable(10L, "CUSTOMER", REQUESTED_AT)).isTrue();
        assertThat(service.isPaymentCancellationAvailable(10L, "ADMIN_REJECTION", REQUESTED_AT)).isTrue();
        assertThat(service.isPaymentCancellationAvailable(10L, "ADMIN", REQUESTED_AT)).isFalse();
    }

    @Test
    void isPaymentCancellationAvailable_inProductionCustomOrder_blocksEveryRefundAction() {
        Order order = order(3L);
        order.setOrderType(OrderType.CUSTOM);
        order.setStatus(OrderStatus.IN_PRODUCTION);
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order));

        assertThat(service.isPaymentCancellationAvailable(10L, "CUSTOMER", REQUESTED_AT)).isFalse();
        assertThat(service.isPaymentCancellationAvailable(10L, "ADMIN_REJECTION", REQUESTED_AT)).isFalse();
        assertThat(service.isPaymentCancellationAvailable(10L, "ADMIN", REQUESTED_AT)).isFalse();
    }

    @Test
    void completeGeneralPaymentCancellation_restoresOnlyRecordedStock() {
        Order order = order(3L);
        OrderItem deductedItem = deductedItem();
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(orderMapper.cancelIfCurrent(
                10L,
                OrderStatus.READY_FOR_PICKUP,
                "CUSTOMER",
                "단순 변심",
                REQUESTED_AT,
                CANCELED_AT
        )).thenReturn(1);
        when(orderMapper.findStockDeductedItemsForRestore(10L)).thenReturn(List.of(deductedItem));
        when(orderMapper.markStockRestoredIfDeducted(100L, CANCELED_AT)).thenReturn(1);

        assertThat(service.completeGeneralPaymentCancellation(
                10L,
                "CUSTOMER",
                "단순 변심",
                REQUESTED_AT,
                CANCELED_AT
        )).isTrue();

        verify(couponOrderCommandService).restoreCouponForCanceledOrder(10L);
        verify(productStockService).restoreStock(1L, 2);
        verify(orderMapper).markStockRestoredIfDeducted(100L, CANCELED_AT);
    }

    @Test
    void completeGeneralPaymentCancellation_afterPickup_doesNotChangeOrder() {
        Order order = order(3L);
        order.setPickupAt(REQUESTED_AT);
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order));

        assertThat(service.completeGeneralPaymentCancellation(
                10L,
                "CUSTOMER",
                "단순 변심",
                REQUESTED_AT,
                CANCELED_AT
        )).isFalse();

        verify(orderMapper, never()).cancelIfCurrent(
                anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void completeGeneralPaymentCancellation_underReviewCustomOrder_doesNotUseCustomCancellationPath() {
        Order order = order(3L);
        order.setOrderType(OrderType.CUSTOM);
        order.setStatus(OrderStatus.UNDER_REVIEW);
        when(orderMapper.findOrderByIdForUpdate(10L)).thenReturn(Optional.of(order));

        assertThat(service.completeGeneralPaymentCancellation(
                10L,
                "CUSTOMER",
                "단순 변심",
                REQUESTED_AT,
                CANCELED_AT
        )).isFalse();

        verify(orderMapper, never()).cancelCustomIfUnderReview(
                anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(couponOrderCommandService, never()).restoreCouponForCanceledOrder(10L);
    }

    private Order order(long memberId) {
        Order order = new Order();
        order.setId(10L);
        order.setMemberId(memberId);
        order.setOrderType(OrderType.GENERAL);
        order.setStatus(OrderStatus.READY_FOR_PICKUP);
        order.setPickupAt(REQUESTED_AT.plusHours(1));
        return order;
    }

    private OrderItem deductedItem() {
        OrderItem item = new OrderItem();
        item.setId(100L);
        item.setOrderId(10L);
        item.setProductId(1L);
        item.setProductType(ProductType.GENERAL);
        item.setQuantity(2);
        return item;
    }
}
