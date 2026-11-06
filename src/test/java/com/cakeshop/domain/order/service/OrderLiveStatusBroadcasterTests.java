package com.cakeshop.domain.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.dto.OrderLiveStatusEvent;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.mapper.OrderMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

// 돈워리 판정으로 주문 상태가 바뀌면 관리자 화면과 그 주문 고객의 화면에 주문 ID만 보낸다.
class OrderLiveStatusBroadcasterTests {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderLiveStatusBroadcaster broadcaster = new OrderLiveStatusBroadcaster(messaging, orderMapper);

    @Test
    void statusChanged_notifiesAdminsAndOrderOwnerWithOrderIdOnly() {
        Order order = new Order();
        order.setId(5L);
        order.setMemberId(10L);
        when(orderMapper.findOrderById(5L)).thenReturn(Optional.of(order));

        broadcaster.on(new OrderLiveStatusEvent(5L));

        verify(messaging).convertAndSend("/topic/admin/orders", (Object) Map.of("orderId", 5L));
        verify(messaging).convertAndSend("/topic/orders/10", (Object) Map.of("orderId", 5L));
    }

    @Test
    void missingOrder_notifiesAdminsOnly() {
        when(orderMapper.findOrderById(5L)).thenReturn(Optional.empty());

        broadcaster.on(new OrderLiveStatusEvent(5L));

        verify(messaging).convertAndSend("/topic/admin/orders", (Object) Map.of("orderId", 5L));
        verify(messaging, never()).convertAndSend(org.mockito.ArgumentMatchers.startsWith("/topic/orders/"), any(Object.class));
    }

    // 보내기에 실패해도 이미 커밋된 주문 처리를 깨뜨리지 않는다
    @Test
    void sendFailure_isSwallowed() {
        doThrow(new IllegalStateException("broker down")).when(messaging).convertAndSend(anyString(), any(Object.class));

        broadcaster.on(new OrderLiveStatusEvent(5L));
    }
}
