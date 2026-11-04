package com.cakeshop.domain.order.service.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.dto.view.OrderDetailView;
import com.cakeshop.domain.order.dto.view.OrderListView;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.OrderViewAssembler;
import com.cakeshop.domain.order.service.customer.OrderCustomerService;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderCustomerServiceTests {

    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 8, 3, 10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private OrderMapper orderMapper;

    private OrderCustomerService orderQueryService;

    @BeforeEach
    void setUp() {
        orderQueryService = new OrderCustomerService(new OrderViewAssembler(orderMapper, CLOCK));
    }

    @Test
    void getMemberOrders_ownedOrders_mapsSnapshotSummary() {
        Order order = order(10L, 3L);
        OrderItem item = item(100L, "딸기 케이크");
        when(orderMapper.findOrdersByMemberId(3L)).thenReturn(List.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of(item));

        List<OrderListView> result = orderQueryService.getMemberOrders(3L);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.orderId()).isEqualTo(10L);
            assertThat(view.productName()).isEqualTo("딸기 케이크");
            assertThat(view.statusLabel()).isEqualTo("픽업 준비");
            assertThat(view.itemCount()).isEqualTo(1);
        });
    }

    @Test
    void getMemberOrder_otherMembersOrder_returnsNotFoundWithoutReadingItems() {
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order(10L, 99L)));

        assertThatThrownBy(() -> orderQueryService.getMemberOrder(3L, 10L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND)
                );

        verify(orderMapper, never()).findOrderItemsByOrderId(10L);
        verify(orderMapper, never()).findOrderItemOptionsByOrderId(10L);
        verify(orderMapper, never()).findOrderItemImagesByOrderId(10L);
    }

    @Test
    void getMemberOrder_ownedOrder_mapsItemsAndOptions() {
        Order order = order(10L, 3L);
        OrderItem item = item(100L, "딸기 케이크");
        OrderItemOption option = new OrderItemOption();
        option.setOrderItemId(100L);
        option.setOptionGroupName("크기");
        option.setOptionName("2호");
        option.setAdditionalPrice(BigDecimal.valueOf(5_000));
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of(item));
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of(option));

        OrderDetailView result = orderQueryService.getMemberOrder(3L, 10L);

        assertThat(result.items()).singleElement().satisfies(itemView -> {
            assertThat(itemView.productName()).isEqualTo("딸기 케이크");
            assertThat(itemView.options()).singleElement().satisfies(optionView ->
                    assertThat(optionView.optionName()).isEqualTo("2호"));
        });
    }

    @Test
    void getMemberOrder_customOrderUnderReview_exposesCustomerCancellationOnly() {
        Order order = order(10L, 3L);
        order.setOrderType(OrderType.CUSTOM);
        order.setStatus(OrderStatus.UNDER_REVIEW);
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());

        OrderDetailView result = orderQueryService.getMemberOrder(3L, 10L);

        assertThat(result.cancelRequestAvailable()).isTrue();
        assertThat(result.isAdminCancellationAvailable()).isFalse();
    }

    // PG(돈워리)가 결제를 확인 중인 일반 주문: 구매자에게는 주문 확인 중, 픽업 전이면 고객·가맹점 취소 가능 (DonWorry ADR-0064 4절)
    @Test
    void getMemberOrder_heldGeneralOrderUnderReview_showsCheckingAndAllowsCancellation() {
        Order order = order(10L, 3L);
        order.setStatus(OrderStatus.UNDER_REVIEW);
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());

        OrderDetailView result = orderQueryService.getMemberOrder(3L, 10L);

        assertThat(result.statusLabel()).isEqualTo("주문 확인 중");
        assertThat(result.cancelRequestAvailable()).isTrue();
        assertThat(result.isAdminCancellationAvailable()).isTrue();
    }

    @Test
    void getMemberOrder_expiredPayment_doesNotExposePaymentButton() {
        Order order = order(10L, 3L);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPaymentExpiresAt(LocalDateTime.of(2026, 8, 3, 9, 59));
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());

        OrderDetailView result = orderQueryService.getMemberOrder(3L, 10L);

        assertThat(result.paymentPending()).isFalse();
    }

    @Test
    void getMemberOrder_customPendingPayment_exposesPaymentButton() {
        Order order = order(10L, 3L);
        order.setOrderType(OrderType.CUSTOM);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPaymentExpiresAt(LocalDateTime.of(2026, 8, 3, 10, 10));
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());

        OrderDetailView result = orderQueryService.getMemberOrder(3L, 10L);

        assertThat(result.paymentPending()).isTrue();
    }

    @Test
    void getMemberOrder_generalPendingPayment_exposesPaymentButton() {
        Order order = order(10L, 3L);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPaymentExpiresAt(LocalDateTime.of(2026, 8, 3, 10, 10));
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());

        OrderDetailView result = orderQueryService.getMemberOrder(3L, 10L);

        assertThat(result.paymentPending()).isTrue();
    }

    @Test
    void getMemberOrder_requestedCancellationAfterPickup_exposesRetryAction() {
        Order order = order(10L, 3L);
        order.setPickupAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        when(orderMapper.findOrderById(10L)).thenReturn(Optional.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.hasRequestedRefundCancellation(10L)).thenReturn(true);

        OrderDetailView result = orderQueryService.getMemberOrder(3L, 10L);

        assertThat(result.cancelRequestAvailable()).isTrue();
    }

    private Order order(long orderId, long memberId) {
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNumber("ORD-10");
        order.setMemberId(memberId);
        order.setOrderType(OrderType.GENERAL);
        order.setStatus(OrderStatus.READY_FOR_PICKUP);
        order.setOrdererName("주문자");
        order.setOrdererPhone("010-1111-2222");
        order.setPickupName("픽업자");
        order.setPickupPhone("010-3333-4444");
        order.setOriginalAmount(BigDecimal.valueOf(35_000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setFinalAmount(BigDecimal.valueOf(35_000));
        order.setPickupAt(LocalDateTime.of(2026, 8, 3, 14, 0));
        order.setCreatedAt(LocalDateTime.of(2026, 8, 2, 10, 0));
        return order;
    }

    private OrderItem item(long itemId, String productName) {
        OrderItem item = new OrderItem();
        item.setId(itemId);
        item.setOrderId(10L);
        item.setProductId(1L);
        item.setProductName(productName);
        item.setProductType(ProductType.GENERAL);
        item.setQuantity(1);
        item.setBasePrice(BigDecimal.valueOf(30_000));
        item.setOptionAmount(BigDecimal.valueOf(5_000));
        item.setTotalAmount(BigDecimal.valueOf(35_000));
        return item;
    }
}
