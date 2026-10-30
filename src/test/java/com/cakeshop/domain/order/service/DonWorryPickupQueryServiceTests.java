package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.mapper.OrderMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DonWorryPickupQueryServiceTests {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final DonWorryPickupQueryService service = new DonWorryPickupQueryService(orders);

    @Test
    void scheduleUsesStoredSnapshotAndConvertsSeoulTimeOnce() {
        var order = order();
        order.setPickupAt(LocalDateTime.parse("2026-11-01T15:00:00.123456"));
        when(orders.findOrderById(1)).thenReturn(Optional.of(order));
        when(orders.findOrderItemsByOrderId(1)).thenReturn(List.of(item("같은 이름", 2), item("같은 이름", 1)));
        var result = service.getMessage(1, false);
        assertThat(result.orderNumber()).isEqualTo("ORD-1");
        assertThat(result.scheduledPickupAt()).isEqualTo(Instant.parse("2026-11-01T06:00:00.123456Z"));
        assertThat(result.items()).extracting(i -> i.quantity()).containsExactly(2, 1);
        assertThat(result.items()).extracting(i -> i.productName()).containsExactly("같은 이름", "같은 이름");
    }

    @Test
    void completionUsesStoredCompletionTimeWithoutReloadingProducts() {
        var order = order();
        order.setPickedUpAt(LocalDateTime.parse("2026-11-01T15:15:00"));
        when(orders.findOrderById(1)).thenReturn(Optional.of(order));
        assertThat(service.getMessage(1, true).pickedUpAt()).isEqualTo(Instant.parse("2026-11-01T06:15:00Z"));
        verify(orders, never()).findOrderItemsByOrderId(1);
    }

    @Test
    void missingCompletionTimeIsNotReplacedWithCurrentTime() {
        when(orders.findOrderById(1)).thenReturn(Optional.of(order()));
        assertThatThrownBy(() -> service.getMessage(1, true)).isInstanceOf(IllegalStateException.class);
    }

    private Order order() {
        var order = new Order();
        order.setOrderNumber("ORD-1");
        return order;
    }

    private OrderItem item(String name, int quantity) {
        var item = new OrderItem();
        item.setProductName(name);
        item.setQuantity(quantity);
        return item;
    }
}
