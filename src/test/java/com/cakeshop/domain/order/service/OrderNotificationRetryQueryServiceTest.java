package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.mapper.OrderMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderNotificationRetryQueryServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-16T13:00:00");
    private final OrderMapper mapper = mock(OrderMapper.class);
    private final OrderNotificationRetryQueryService service = new OrderNotificationRetryQueryService(mapper);

    @Test
    void onlyRetriesPickupWhileItsOriginalDayAndOrderStatusAreCurrent() {
        var order = mock(Order.class);
        when(mapper.findOrderById(1L)).thenReturn(Optional.of(order));
        when(order.getPickupAt()).thenReturn(NOW.plusHours(1));
        when(order.getStatus()).thenReturn(OrderStatus.READY_FOR_PICKUP);

        assertThat(service.isCurrent(1L, "CUSTOMER_PICKUP_REMINDER_TODAY", NOW.minusMinutes(5), NOW)).isTrue();
        assertThat(service.isCurrent(1L, "CUSTOMER_PICKUP_REMINDER_TOMORROW", NOW.minusDays(1), NOW)).isFalse();
        assertThat(service.isCurrent(1L, "CUSTOMER_PICKUP_REMINDER_TODAY", NOW, NOW.plusHours(1))).isFalse();

        when(order.getStatus()).thenReturn(OrderStatus.PICKED_UP);
        assertThat(service.isCurrent(1L, "CUSTOMER_PICKUP_REMINDER_TODAY", NOW, NOW)).isFalse();
    }

    @Test
    void canceledOrderSuppressesPaidMessageAndAllowsCancellationMessage() {
        var order = mock(Order.class);
        when(mapper.findOrderById(1L)).thenReturn(Optional.of(order));
        when(order.getStatus()).thenReturn(OrderStatus.CANCELED);

        assertThat(service.isCurrent(1L, "ORDER_PAID", NOW, NOW)).isFalse();
        assertThat(service.isCurrent(1L, "ORDER_CANCELED", NOW, NOW)).isTrue();
    }
}
