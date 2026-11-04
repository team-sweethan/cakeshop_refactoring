package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.dto.view.OrderDetailView;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.customer.OrderCustomerService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderPaymentQueryServiceTests {

    @Mock
    private OrderCustomerService orderCustomerService;

    @Mock
    private MemberService memberService;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderDetailView order;

    @Mock
    private OrderDetailView.Item item;

    @Mock
    private OrderDetailView.Option option;

    @Test
    void getMemberPaymentOrder_ownedPendingGeneralOrder_returnsMinimalPaymentContract() {
        LocalDateTime pickupAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        LocalDateTime paymentExpiresAt = LocalDateTime.of(2026, 8, 3, 10, 10);
        when(orderCustomerService.getMemberOrder(10L, 1L)).thenReturn(order);
        when(order.orderId()).thenReturn(1L);
        when(order.orderNumber()).thenReturn("ORD-100");
        when(order.orderType()).thenReturn(OrderType.GENERAL);
        when(order.status()).thenReturn(OrderStatus.PENDING_PAYMENT);
        when(order.originalAmount()).thenReturn(BigDecimal.valueOf(30_000));
        when(order.discountAmount()).thenReturn(BigDecimal.ZERO);
        when(order.finalAmount()).thenReturn(BigDecimal.valueOf(30_000));
        when(order.pickupAt()).thenReturn(pickupAt);
        when(order.paymentExpiresAt()).thenReturn(paymentExpiresAt);
        when(order.ordererName()).thenReturn("홍길동");
        when(order.ordererPhone()).thenReturn("010-1111-2222");
        when(order.pickupName()).thenReturn("김픽업");
        when(order.pickupPhone()).thenReturn("010-3333-4444");
        when(order.items()).thenReturn(List.of(item));
        when(item.productName()).thenReturn("딸기 생크림 케이크");
        when(item.quantity()).thenReturn(1);
        when(item.totalAmount()).thenReturn(BigDecimal.valueOf(30_000));
        when(item.options()).thenReturn(List.of(option));
        when(option.groupName()).thenReturn("케이크 크기");
        when(option.optionName()).thenReturn("1호");

        OrderPaymentQueryService service = new OrderPaymentQueryService(
                orderCustomerService,
                memberService,
                orderMapper
        );

        var result = service.getMemberPaymentOrder(10L, 1L);

        assertThat(result.generalOrder()).isTrue();
        assertThat(result.pendingPayment()).isTrue();
        // 돈워리 결제 준비 요청에 실을 주문자·픽업자
        assertThat(result.ordererName()).isEqualTo("홍길동");
        assertThat(result.ordererPhone()).isEqualTo("010-1111-2222");
        assertThat(result.pickupName()).isEqualTo("김픽업");
        assertThat(result.pickupPhone()).isEqualTo("010-3333-4444");
        assertThat(result.finalAmount()).isEqualByComparingTo("30000");
        assertThat(result.items()).singleElement().satisfies(savedItem -> {
            assertThat(savedItem.productName()).isEqualTo("딸기 생크림 케이크");
            assertThat(savedItem.options()).singleElement()
                    .satisfies(savedOption -> assertThat(savedOption.optionName()).isEqualTo("1호"));
        });
    }

    @Test
    void getMemberGeneralPaymentOrder_returnsOnlyPaymentExecutionData() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 3, 10, 10);
        when(memberService.isActiveMember(10L)).thenReturn(true);
        Order paymentOrder = new Order();
        paymentOrder.setId(1L);
        paymentOrder.setMemberId(10L);
        paymentOrder.setOrderType(OrderType.GENERAL);
        paymentOrder.setStatus(OrderStatus.PENDING_PAYMENT);
        paymentOrder.setFinalAmount(BigDecimal.valueOf(30_000));
        paymentOrder.setPaymentExpiresAt(expiresAt);
        OrderItem paymentItem = new OrderItem();
        paymentItem.setId(20L);
        paymentItem.setProductId(30L);
        paymentItem.setProductType(com.cakeshop.domain.product.entity.ProductType.GENERAL);
        paymentItem.setQuantity(2);
        when(orderMapper.findOrderById(1L)).thenReturn(Optional.of(paymentOrder));
        when(orderMapper.findOrderItemsByOrderId(1L)).thenReturn(List.of(paymentItem));

        OrderPaymentQueryService service = new OrderPaymentQueryService(
                orderCustomerService,
                memberService,
                orderMapper
        );

        var result = service.getMemberGeneralPaymentOrder(10L, 1L);

        assertThat(result.amount()).isEqualByComparingTo("30000");
        assertThat(result.paymentExpiresAt()).isEqualTo(expiresAt);
        assertThat(result.products()).containsExactly(
                new OrderPaymentQueryService.PaymentProduct(20L, 30L, 2)
        );
    }

    @Test
    void getMemberPaymentExecutionOrder_customOrder_returnsCustomStockSnapshot() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 3, 10, 10);
        when(memberService.isActiveMember(10L)).thenReturn(true);
        Order paymentOrder = new Order();
        paymentOrder.setId(1L);
        paymentOrder.setMemberId(10L);
        paymentOrder.setOrderType(OrderType.CUSTOM);
        paymentOrder.setStatus(OrderStatus.PENDING_PAYMENT);
        paymentOrder.setFinalAmount(BigDecimal.valueOf(30_000));
        paymentOrder.setPaymentExpiresAt(expiresAt);
        OrderItem paymentItem = new OrderItem();
        paymentItem.setId(20L);
        paymentItem.setProductId(30L);
        paymentItem.setProductType(com.cakeshop.domain.product.entity.ProductType.CUSTOM);
        paymentItem.setQuantity(1);
        when(orderMapper.findOrderById(1L)).thenReturn(Optional.of(paymentOrder));
        when(orderMapper.findOrderItemsByOrderId(1L)).thenReturn(List.of(paymentItem));

        OrderPaymentQueryService service = new OrderPaymentQueryService(
                orderCustomerService,
                memberService,
                orderMapper
        );

        var result = service.getMemberPaymentExecutionOrder(10L, 1L);

        assertThat(result.orderType()).isEqualTo(OrderType.CUSTOM);
        assertThat(result.products()).containsExactly(
                new OrderPaymentQueryService.PaymentProduct(20L, 30L, 1)
        );
    }
}
