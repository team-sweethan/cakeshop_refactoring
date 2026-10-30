package com.cakeshop.domain.order.service.customer;

import com.cakeshop.domain.order.dto.DonWorryPickupEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.dto.form.customer.OrderCustomCreateForm;
import com.cakeshop.domain.order.dto.view.customer.OrderCreationResult;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy;
import com.cakeshop.domain.payment.service.PaymentOrderPreparationCommandService;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerCustomOrderServiceTests {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T00:00:00Z"), ZoneId.of("Asia/Seoul")
    );

    @Mock private PickupAvailabilityPolicy pickupAvailabilityPolicy;
    @Mock private ProductQueryService productQueryService;
    @Mock private OrderOptionValidator orderOptionValidator;
    @Mock private OrderMapper orderMapper;
    @Mock private PaymentOrderPreparationCommandService paymentPreparationCommandService;
    @Mock private MemberService memberService;
    @Mock private MemberCouponQueryService memberCouponQueryService;
    @Mock private CouponOrderCommandService couponOrderCommandService;

    @Mock
    private ApplicationEventPublisher events;

    private CustomerCustomOrderService service;

    @BeforeEach
    void setUp() {
        service = new CustomerCustomOrderService(
                pickupAvailabilityPolicy, productQueryService, orderOptionValidator, orderMapper,
                paymentPreparationCommandService, memberService, memberCouponQueryService,
                couponOrderCommandService, CLOCK, events
        );
    }

    private void stubNewOrder() {
        when(memberCouponQueryService.lockActiveCouponIssuableMember(10L)).thenReturn(true);
        when(memberService.isActiveMember(10L)).thenReturn(true);
        when(orderMapper.findOrderByMemberIdAndRequestKey(eq(10L), any())).thenReturn(Optional.empty());
        when(pickupAvailabilityPolicy.isAvailable(any())).thenReturn(true);
        when(productQueryService.getSalesInfo(6L)).thenReturn(new ProductSalesInfo(
                6L, "레터링 케이크", ProductType.CUSTOM, 2, true,
                BigDecimal.valueOf(55_000), null
        ));
        when(orderOptionValidator.validate(6L, List.of())).thenReturn(List.of());
        when(orderMapper.insertOrder(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(20L);
            return 1;
        });
        when(orderMapper.existsOrderItemByOrderId(20L)).thenReturn(false);
        when(orderMapper.insertOrderItem(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, com.cakeshop.domain.order.entity.OrderItem.class).setId(30L);
            return 1;
        });
    }

    @Test
    void createCustomOrder_validRequest_savesServerCalculatedSnapshotAndReadyPayment() {
        stubNewOrder();
        OrderCreationResult result = service.createCustomOrder(10L, form());
        verify(events).publishEvent(new DonWorryPickupEvent(result.orderId(), false));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insertOrder(orderCaptor.capture());
        assertThat(result.orderId()).isEqualTo(20L);
        assertThat(result.requiresPendingPaymentGuide()).isFalse();
        org.assertj.core.api.Assertions.assertThat(orderCaptor.getValue().getOrderType())
                .isEqualTo(com.cakeshop.domain.order.entity.OrderType.CUSTOM);
        org.assertj.core.api.Assertions.assertThat(orderCaptor.getValue().getFinalAmount())
                .isEqualByComparingTo("55000");
        verify(paymentPreparationCommandService).prepareReadyPayment(
                eq(20L), eq(orderCaptor.getValue().getOrderNumber()), eq(BigDecimal.valueOf(55_000))
        );
    }

    @Test
    void createCustomOrder_couponMakesFinalAmountZero_rejectsBeforeReadyPayment() {
        stubNewOrder();
        OrderCustomCreateForm form = form();
        form.setMemberCouponId(101L);
        when(couponOrderCommandService.reserveCouponForOrder(
                10L, 101L, 20L, BigDecimal.valueOf(55_000)
        )).thenReturn(BigDecimal.valueOf(55_000));

        assertThatThrownBy(() -> service.createCustomOrder(10L, form))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(OrderErrorCode.INVALID_ORDER_AMOUNT);

        verify(paymentPreparationCommandService, never()).prepareReadyPayment(anyLong(), any(), any());
    }

    @Test
    void createCustomOrder_changedDisplayedAmount_rejectsBeforeSavingOrder() {
        when(memberCouponQueryService.lockActiveCouponIssuableMember(10L)).thenReturn(true);
        when(memberService.isActiveMember(10L)).thenReturn(true);
        when(orderMapper.findOrderByMemberIdAndRequestKey(eq(10L), any())).thenReturn(Optional.empty());
        when(productQueryService.getSalesInfo(6L)).thenReturn(new ProductSalesInfo(
                6L, "레터링 케이크", ProductType.CUSTOM, 2, true,
                BigDecimal.valueOf(55_000), null
        ));
        when(orderOptionValidator.validate(6L, List.of())).thenReturn(List.of());
        OrderCustomCreateForm form = form();
        form.setDisplayedOriginalAmount(BigDecimal.valueOf(60_000));

        assertThatThrownBy(() -> service.createCustomOrder(10L, form))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_AMOUNT_CHANGED);

        verify(orderMapper, never()).insertOrder(any());
        verify(paymentPreparationCommandService, never()).prepareReadyPayment(anyLong(), any(), any());
    }

    @Test
    void createCustomOrder_sameRequestKey_returnsExistingOrderWithoutDuplicateSideEffects() {
        when(memberCouponQueryService.lockActiveCouponIssuableMember(10L)).thenReturn(true);
        when(memberService.isActiveMember(10L)).thenReturn(true);
        Order existing = new Order();
        existing.setId(99L);
        when(orderMapper.findOrderByMemberIdAndRequestKey(eq(10L), any()))
                .thenReturn(Optional.of(existing));

        long orderId = service.createCustomOrder(10L, form()).orderId();

        org.assertj.core.api.Assertions.assertThat(orderId).isEqualTo(99L);
        verify(orderMapper, never()).insertOrder(any());
        verify(paymentPreparationCommandService, never()).prepareReadyPayment(anyLong(), any(), any());
    }

    @Test
    void createCustomOrder_otherRequestKeyWithPendingPayment_returnsGuideWithoutDuplicateWrites() {
        when(memberCouponQueryService.lockActiveCouponIssuableMember(10L)).thenReturn(true);
        when(memberService.isActiveMember(10L)).thenReturn(true);
        when(orderMapper.findOrderByMemberIdAndRequestKey(eq(10L), any())).thenReturn(Optional.empty());
        Order pendingOrder = new Order();
        pendingOrder.setId(98L);
        pendingOrder.setOrderNumber("ORD-CUSTOM-PENDING");
        pendingOrder.setPaymentExpiresAt(LocalDateTime.of(2026, 8, 11, 9, 10));
        when(orderMapper.findPendingPaymentOrderByMemberId(eq(10L), any()))
                .thenReturn(Optional.of(pendingOrder));

        OrderCreationResult result = service.createCustomOrder(10L, form());

        assertThat(result.requiresPendingPaymentGuide()).isTrue();
        assertThat(result.orderId()).isEqualTo(98L);
        verify(orderMapper, never()).insertOrder(any());
        verify(paymentPreparationCommandService, never()).prepareReadyPayment(anyLong(), any(), any());
    }

    @Test
    void createCustomOrder_pickupBeforePreparationPeriod_rejectsBeforeSavingOrder() {
        when(memberCouponQueryService.lockActiveCouponIssuableMember(10L)).thenReturn(true);
        when(memberService.isActiveMember(10L)).thenReturn(true);
        when(orderMapper.findOrderByMemberIdAndRequestKey(eq(10L), any())).thenReturn(Optional.empty());
        when(productQueryService.getSalesInfo(6L)).thenReturn(new ProductSalesInfo(
                6L, "레터링 케이크", ProductType.CUSTOM, 2, true,
                BigDecimal.valueOf(55_000), null
        ));
        when(orderOptionValidator.validate(6L, List.of())).thenReturn(List.of());
        OrderCustomCreateForm form = form();
        form.setPickupAt(LocalDateTime.of(2026, 8, 11, 10, 30));

        assertThatThrownBy(() -> service.createCustomOrder(10L, form))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_INPUT);

        verify(orderMapper, never()).insertOrder(any());
    }

    private OrderCustomCreateForm form() {
        OrderCustomCreateForm form = new OrderCustomCreateForm();
        form.setRequestKey(UUID.randomUUID().toString());
        form.setProductId(6L);
        form.setOptionIds(List.of());
        form.setOrdererName("주문자");
        form.setOrdererPhone("010-1111-2222");
        form.setPickupName("픽업자");
        form.setPickupPhone("010-3333-4444");
        form.setPickupAt(LocalDateTime.of(2026, 8, 13, 10, 30));
        form.setDisplayedOriginalAmount(BigDecimal.valueOf(55_000));
        return form;
    }
}
