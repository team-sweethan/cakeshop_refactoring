package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.mapper.OrderMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DonWorryPickupQueryServiceTests {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final DonWorryPickupQueryService service = new DonWorryPickupQueryService(orders);

    @Test
    void readsStoredItemsWithoutPickupTimes() {
        var order = order();
        when(orders.findOrderById(1)).thenReturn(Optional.of(order));
        when(orders.findOrderItemsByOrderId(1)).thenReturn(List.of(item("같은 이름", 2), item("같은 이름", 1)));
        var result = service.getMessage(1);
        assertThat(result.orderNumber()).isEqualTo("ORD-1");
        assertThat(result.items()).extracting(i -> i.quantity()).containsExactly(2, 1);
        assertThat(result.items()).extracting(i -> i.productName()).containsExactly("같은 이름", "같은 이름");
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
