package com.cakeshop.domain.order.service.admin;

import com.cakeshop.domain.order.dto.DonWorryPickupEvent;
import org.springframework.context.ApplicationEventPublisher;

import com.cakeshop.domain.order.service.admin.FulfillmentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.dto.form.admin.FulfillmentSearchCondition;
import com.cakeshop.domain.order.dto.view.admin.FulfillmentListView;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FulfillmentServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 15, 0);
    private static final Clock CLOCK = Clock.fixed(
            NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ApplicationEventPublisher events;

    private FulfillmentService fulfillmentService;

    @BeforeEach
    void setUp() {
        fulfillmentService = new FulfillmentService(orderMapper, CLOCK, events);
    }

    @Test
    void markPickedUp_readyOrder_recordsAdminAndTime() {
        when(orderMapper.markPickedUpIfReady(10L, 7L, NOW)).thenReturn(1);

        fulfillmentService.markPickedUp(10L, 7L);

        verify(orderMapper).markPickedUpIfReady(10L, 7L, NOW);
        verify(events).publishEvent(new DonWorryPickupEvent(10L, true));
    }

    @Test
    void markPickedUp_nonReadyOrder_rejectsStateTransition() {
        when(orderMapper.markPickedUpIfReady(10L, 7L, NOW)).thenReturn(0);

        assertThatThrownBy(() -> fulfillmentService.markPickedUp(10L, 7L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(OrderErrorCode.INVALID_STATUS_TRANSITION)
                );
    }

    @Test
    void getFulfillments_missingCondition_loadsAllStagesAndMapsOrderSnapshots() {
        Order order = order(OrderStatus.READY_FOR_PICKUP);
        OrderItem item = item();
        OrderItemOption option = option();
        order.setOrderType(OrderType.CUSTOM);
        order.setRequestMessage("초는 하늘색으로");
        item.setRequirements("생일 축하해");
        when(orderMapper.countFulfillmentOrders(null)).thenReturn(1L);
        when(orderMapper.findFulfillmentOrders(null, 20, 0)).thenReturn(List.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of(item));
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of(option));

        FulfillmentListView result = fulfillmentService.getFulfillments(null, new PageRequest(null, null));

        assertThat(result.selectedStatus()).isNull();
        assertThat(result.orders().getContent()).singleElement().satisfies(view -> {
            assertThat(view.orderNumber()).isEqualTo("ORD-10");
            assertThat(view.statusLabel()).isEqualTo("픽업 준비");
            assertThat(view.pickupCompletable()).isTrue();
            assertThat(view.requestMessage()).isEqualTo("초는 하늘색으로");
            assertThat(view.items()).singleElement().satisfies(itemView -> {
                assertThat(itemView.productName()).isEqualTo("딸기 케이크");
                assertThat(itemView.requirements()).isEqualTo("생일 축하해");
                assertThat(itemView.optionSummary()).isEqualTo("크기: 2호");
            });
        });
    }

    @Test
    void getFulfillments_unsupportedStatus_ignoresStatusFilter() {
        FulfillmentSearchCondition condition = new FulfillmentSearchCondition();
        condition.setStatus(OrderStatus.CANCELED);
        when(orderMapper.countFulfillmentOrders(null)).thenReturn(0L);

        FulfillmentListView result = fulfillmentService.getFulfillments(condition, new PageRequest(null, null));

        assertThat(result.selectedStatus()).isNull();
    }

    @Test
    void getFulfillments_futurePickupOrder_exposesCompletionAction() {
        Order order = order(OrderStatus.READY_FOR_PICKUP);
        order.setPickupAt(NOW.plusHours(1));
        when(orderMapper.countFulfillmentOrders(null)).thenReturn(1L);
        when(orderMapper.findFulfillmentOrders(null, 20, 0)).thenReturn(List.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());

        FulfillmentListView result = fulfillmentService.getFulfillments(null, new PageRequest(null, null));

        assertThat(result.orders().getContent()).singleElement()
                .satisfies(view -> assertThat(view.pickupCompletable()).isTrue());
    }

    @Test
    void getFulfillments_requestedCancellation_doesNotExposeCompletionAction() {
        Order order = order(OrderStatus.READY_FOR_PICKUP);
        when(orderMapper.countFulfillmentOrders(null)).thenReturn(1L);
        when(orderMapper.findFulfillmentOrders(null, 20, 0)).thenReturn(List.of(order));
        when(orderMapper.findOrderItemsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.findOrderItemOptionsByOrderId(10L)).thenReturn(List.of());
        when(orderMapper.hasRequestedRefundCancellation(10L)).thenReturn(true);

        FulfillmentListView result = fulfillmentService.getFulfillments(null, new PageRequest(null, null));

        assertThat(result.orders().getContent()).singleElement()
                .satisfies(view -> assertThat(view.pickupCompletable()).isFalse());
    }

    @Test
    void getFulfillmentPage_orderOnSecondPage_returnsPageNumber() {
        when(orderMapper.findFulfillmentPage(10L, OrderStatus.READY_FOR_PICKUP, 20))
                .thenReturn(2);

        int page = fulfillmentService.getFulfillmentPage(10L, OrderStatus.READY_FOR_PICKUP);

        assertThat(page).isEqualTo(2);
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-10");
        order.setOrderType(OrderType.GENERAL);
        order.setStatus(status);
        order.setPickupName("수령자");
        order.setPickupPhone("010-1111-2222");
        order.setPickupAt(LocalDateTime.of(2026, 8, 2, 14, 0));
        return order;
    }

    private OrderItem item() {
        OrderItem item = new OrderItem();
        item.setId(100L);
        item.setOrderId(10L);
        item.setProductName("딸기 케이크");
        item.setProductType(ProductType.GENERAL);
        item.setQuantity(1);
        item.setBasePrice(BigDecimal.valueOf(30_000));
        item.setOptionAmount(BigDecimal.valueOf(5_000));
        item.setTotalAmount(BigDecimal.valueOf(35_000));
        return item;
    }

    private OrderItemOption option() {
        OrderItemOption option = new OrderItemOption();
        option.setOrderItemId(100L);
        option.setOptionGroupName("크기");
        option.setOptionName("2호");
        return option;
    }
}
