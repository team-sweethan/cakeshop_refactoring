package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.dto.DonWorryPickupEvent;
import org.springframework.context.ApplicationEventPublisher;

import com.cakeshop.domain.order.dto.form.customer.OrderCartCreateForm;
import com.cakeshop.domain.order.dto.form.customer.OrderGeneralCreateForm;
import com.cakeshop.domain.order.dto.view.customer.OrderCreationResult;
import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.cart.dto.view.CartOrderItemView;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderCartMapper;
import com.cakeshop.domain.order.dto.view.OrderCartItemLink;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator.ValidatedOption;
import com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy;
import com.cakeshop.domain.payment.service.PaymentOrderPreparationCommandService;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.error.ProductErrorCode;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.store.dto.view.StoreView;
import com.cakeshop.domain.store.entity.StoreHoliday;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

    private static final ZoneId TEST_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FIXED_NOW =
            LocalDateTime.of(2026, 7, 31, 10, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            FIXED_NOW.atZone(TEST_ZONE).toInstant(),
            TEST_ZONE
    );

    @Mock
    private StoreService storeService;

    @Mock
    private ProductQueryService productQueryService;

    @Mock
    private OrderOptionValidator orderOptionValidator;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private PaymentOrderPreparationCommandService paymentPreparationService;

    @Mock
    private MemberService memberService;

    @Mock
    private MemberCouponQueryService memberCouponQueryService;

    @Mock
    private CouponOrderCommandService couponOrderCommandService;

    @Mock
    private CartOrderQueryService cartOrderQueryService;

    @Mock
    private OrderCartMapper orderCartMapper;

    @Mock
    private ApplicationEventPublisher events;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        lenient().when(storeService.getStoreView()).thenReturn(storeView());
        lenient().when(memberService.isActiveMember(anyLong())).thenReturn(true);
        lenient().when(memberCouponQueryService.lockActiveCouponIssuableMember(anyLong())).thenReturn(true);
        orderService = new OrderService(
                new PickupAvailabilityPolicy(storeService),
                productQueryService,
                orderOptionValidator,
                orderMapper,
                paymentPreparationService,
                memberService,
                memberCouponQueryService,
                couponOrderCommandService,
                cartOrderQueryService,
                orderCartMapper,
                FIXED_CLOCK, events
        );
    }

    @Test
    void createGeneralOrderRecalculatesAmountsAndSavesSnapshotsAndReadyPayment() {
        long memberId = 10L;
        ProductSalesInfo product =
                product(
                        1L,
                        ProductType.GENERAL,
                        "딸기 케이크",
                        30_000,
                        2
                );
        when(productQueryService.getSalesInfo(1L)).thenReturn(product);
        when(orderOptionValidator.validate(1L, List.of(101L)))
                .thenReturn(List.of(new ValidatedOption(
                        101L,
                        "크기",
                        "2호",
                        BigDecimal.valueOf(5_000)
                )));
        when(orderMapper.insertOrder(any(Order.class))).thenAnswer(invocation -> {
            invocation.<Order>getArgument(0).setId(100L);
            return 1;
        });
        when(orderMapper.insertOrderItem(any(OrderItem.class))).thenAnswer(invocation -> {
            invocation.<OrderItem>getArgument(0).setId(200L);
            return 1;
        });
        when(orderMapper.insertOrderItemOption(any(OrderItemOption.class))).thenReturn(1);
        OrderGeneralCreateForm form = form(1L, 2, List.of(101L));

        OrderCreationResult result = orderService.createGeneralOrder(memberId, form);

        assertThat(result.orderId()).isEqualTo(100L);
        assertThat(result.requiresPendingPaymentGuide()).isFalse();

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insertOrder(orderCaptor.capture());
        Order order = orderCaptor.getValue();
        assertThat(order.getMemberId()).isEqualTo(memberId);
        assertThat(order.getOrderType()).isEqualTo(OrderType.GENERAL);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getOriginalAmount()).isEqualByComparingTo("70000");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getFinalAmount()).isEqualByComparingTo("70000");
        assertThat(order.getPaymentExpiresAt())
                .isEqualTo(FIXED_NOW.plusMinutes(10));

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderMapper).insertOrderItem(itemCaptor.capture());
        OrderItem savedItem = itemCaptor.getValue();
        assertThat(savedItem.getProductName()).isEqualTo("딸기 케이크");
        assertThat(savedItem.getProductType()).isEqualTo(ProductType.GENERAL);
        assertThat(savedItem.getQuantity()).isEqualTo(2);
        assertThat(savedItem.getBasePrice()).isEqualByComparingTo("30000");
        assertThat(savedItem.getOptionAmount()).isEqualByComparingTo("5000");
        assertThat(savedItem.getTotalAmount()).isEqualByComparingTo("70000");

        ArgumentCaptor<OrderItemOption> optionCaptor =
                ArgumentCaptor.forClass(OrderItemOption.class);
        verify(orderMapper).insertOrderItemOption(optionCaptor.capture());
        assertThat(optionCaptor.getValue()).satisfies(snapshot -> {
            assertThat(snapshot.getOrderItemId()).isEqualTo(200L);
            assertThat(snapshot.getProductOptionId()).isEqualTo(101L);
            assertThat(snapshot.getOptionGroupName()).isEqualTo("크기");
            assertThat(snapshot.getOptionName()).isEqualTo("2호");
            assertThat(snapshot.getAdditionalPrice()).isEqualByComparingTo("5000");
        });

        verify(paymentPreparationService).prepareReadyPayment(
                100L,
                order.getOrderNumber(),
                order.getFinalAmount()
        );

        verify(events).publishEvent(new DonWorryPickupEvent(100L, false));
        InOrder saveOrder = inOrder(
                productQueryService,
                orderOptionValidator,
                orderMapper,
                paymentPreparationService
        );
        saveOrder.verify(productQueryService).getSalesInfo(1L);
        saveOrder.verify(orderOptionValidator)
                .validate(1L, List.of(101L));
        saveOrder.verify(orderMapper).insertOrder(any(Order.class));
        saveOrder.verify(orderMapper).insertOrderItem(any(OrderItem.class));
        saveOrder.verify(orderMapper).insertOrderItemOption(any(OrderItemOption.class));
        saveOrder.verify(paymentPreparationService).prepareReadyPayment(
                100L,
                order.getOrderNumber(),
                order.getFinalAmount()
        );
    }

    @Test
    void createCartOrderReloadsSelectedCartItemsAndSavesEveryItem() {
        when(cartOrderQueryService.getSelectedOrderItems(10L, List.of(11L, 12L)))
                .thenReturn(List.of(
                        new CartOrderItemView(11L, 1L, 1, "문 앞", List.of()),
                        new CartOrderItemView(12L, 2L, 2, null, List.of())
                ));
        when(productQueryService.getSalesInfo(1L)).thenReturn(product(1L, ProductType.GENERAL, "상품1", 10_000, 5));
        when(productQueryService.getSalesInfo(2L)).thenReturn(product(2L, ProductType.GENERAL, "상품2", 20_000, 5));
        when(orderOptionValidator.validate(anyLong(), any())).thenReturn(List.of());
        when(orderMapper.insertOrder(any(Order.class))).thenAnswer(invocation -> {
            invocation.<Order>getArgument(0).setId(100L);
            return 1;
        });
        when(orderMapper.insertOrderItem(any(OrderItem.class))).thenAnswer(invocation -> {
            invocation.<OrderItem>getArgument(0).setId(200L);
            return 1;
        });
        when(orderCartMapper.insertOrderCartItems(
                org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.anyList()
        )).thenReturn(2);

        OrderCartCreateForm form = new OrderCartCreateForm();
        form.setRequestKey(UUID.randomUUID().toString());
        form.setCartItemIds(List.of(11L, 12L));
        form.setOrdererName("주문자");
        form.setOrdererPhone("010-1234-5678");
        form.setPickupName("픽업자");
        form.setPickupPhone("010-9876-5432");
        form.setPickupAt(FIXED_NOW.plusHours(1));
        form.setDisplayedOriginalAmount(BigDecimal.valueOf(50_000));

        OrderCreationResult result = orderService.createCartOrder(10L, form);
        verify(events).publishEvent(new DonWorryPickupEvent(result.orderId(), false));

        assertThat(result.orderId()).isEqualTo(100L);
        assertThat(result.requiresPendingPaymentGuide()).isFalse();
        verify(cartOrderQueryService).getSelectedOrderItems(10L, List.of(11L, 12L));
        verify(orderCartMapper).insertOrderCartItems(100L, List.of(
                new OrderCartItemLink(11L, 1),
                new OrderCartItemLink(12L, 2)
        ));
        ArgumentCaptor<OrderItem> items = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderMapper, org.mockito.Mockito.times(2)).insertOrderItem(items.capture());
        assertThat(items.getAllValues()).extracting(OrderItem::getTotalAmount)
                .containsExactly(BigDecimal.valueOf(10_000), BigDecimal.valueOf(40_000));
        assertThat(items.getAllValues().getFirst().getRequirements()).isEqualTo("문 앞");
    }

    @Test
    void createGeneralOrder_changedDisplayedAmount_doesNotCreateOrder() {
        when(productQueryService.getSalesInfo(1L)).thenReturn(
                product(1L, ProductType.GENERAL, "딸기 케이크", 30_000, 2)
        );
        when(orderOptionValidator.validate(1L, List.of())).thenReturn(List.of());
        OrderGeneralCreateForm form = form(1L, 1, List.of());
        form.setDisplayedOriginalAmount(BigDecimal.valueOf(29_000));

        assertThatThrownBy(() -> orderService.createGeneralOrder(10L, form))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(OrderErrorCode.ORDER_AMOUNT_CHANGED)
                );

        verify(orderMapper, never()).insertOrder(any(Order.class));
        verify(couponOrderCommandService, never()).reserveCouponForOrder(
                anyLong(), anyLong(), anyLong(), any()
        );
    }

    @Test
    void createGeneralOrderRejectsInvalidMemberIdBeforeProductLookup() {
        when(memberService.isActiveMember(0L)).thenReturn(false);
        assertThatThrownBy(() -> orderService.createGeneralOrder(
                0L,
                form(1L, 1, List.of())
        )).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(OrderErrorCode.MEMBER_NOT_AVAILABLE)
        );

        verify(productQueryService, never()).getSalesInfo(1L);
        verify(orderMapper, never()).insertOrder(any(Order.class));
        verifyNoInteractions(paymentPreparationService);
    }

    @Test
    void createGeneralOrder_sameRequestKey_returnsExistingOrderWithoutDuplicateWrites() {
        OrderGeneralCreateForm form = form(1L, 1, List.of());
        Order existingOrder = new Order();
        existingOrder.setId(77L);
        when(orderMapper.findOrderByMemberIdAndRequestKey(
                10L,
                form.getRequestKey()
        )).thenReturn(Optional.of(existingOrder));

        assertThat(orderService.createGeneralOrder(10L, form).orderId()).isEqualTo(77L);

        verify(productQueryService, never()).getSalesInfo(anyLong());
        verify(orderMapper, never()).insertOrder(any(Order.class));
        verifyNoInteractions(paymentPreparationService);
    }

    @Test
    void createGeneralOrder_otherRequestKeyWithPendingPayment_returnsGuideWithoutDuplicateWrites() {
        Order pendingOrder = pendingPaymentOrder(77L, "ORD-PENDING", FIXED_NOW.plusMinutes(5));
        when(orderMapper.findPendingPaymentOrderByMemberId(10L, FIXED_NOW))
                .thenReturn(Optional.of(pendingOrder));
        OrderGeneralCreateForm form = form(1L, 1, List.of());

        OrderCreationResult result = orderService.createGeneralOrder(10L, form);

        assertThat(result.requiresPendingPaymentGuide()).isTrue();
        assertThat(result.pendingPaymentOrder())
                .isEqualTo(new OrderCreationResult.PendingPaymentOrder(
                        77L, "ORD-PENDING", FIXED_NOW.plusMinutes(5)
                ));
        verify(orderMapper, never()).insertOrder(any(Order.class));
        verifyNoInteractions(paymentPreparationService);
    }

    @Test
    void createGeneralOrderAfterPendingPaymentGuide_skipsPendingPaymentLookupOnce() {
        when(productQueryService.getSalesInfo(1L))
                .thenReturn(product(1L, ProductType.GENERAL, "새 주문 케이크", 30_000, 2));
        when(orderOptionValidator.validate(1L, List.of())).thenReturn(List.of());
        when(orderMapper.insertOrder(any(Order.class))).thenAnswer(invocation -> {
            invocation.<Order>getArgument(0).setId(100L);
            return 1;
        });
        when(orderMapper.existsOrderItemByOrderId(100L)).thenReturn(false);
        when(orderMapper.insertOrderItem(any(OrderItem.class))).thenAnswer(invocation -> {
            invocation.<OrderItem>getArgument(0).setId(200L);
            return 1;
        });

        OrderCreationResult result = orderService.createGeneralOrderAfterPendingPaymentGuide(
                10L,
                form(1L, 1, List.of())
        );

        assertThat(result.orderId()).isEqualTo(100L);
        assertThat(result.requiresPendingPaymentGuide()).isFalse();
        verify(orderMapper, never()).findPendingPaymentOrderByMemberId(anyLong(), any());
        verify(paymentPreparationService).prepareReadyPayment(anyLong(), any(), any());
    }

    @Test
    void createCartOrder_otherRequestKeyWithPendingPayment_returnsGuideWithoutCartWrites() {
        OrderCartCreateForm form = cartForm();
        when(orderMapper.findPendingPaymentOrderByMemberId(10L, FIXED_NOW))
                .thenReturn(Optional.of(pendingPaymentOrder(78L, "ORD-CART-PENDING", FIXED_NOW.plusMinutes(4))));

        OrderCreationResult result = orderService.createCartOrder(10L, form);

        assertThat(result.requiresPendingPaymentGuide()).isTrue();
        assertThat(result.orderId()).isEqualTo(78L);
        verifyNoInteractions(cartOrderQueryService, orderCartMapper, paymentPreparationService);
        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    @Test
    void createGeneralOrder_inactiveMember_rejectsBeforeProductLookup() {
        when(memberService.isActiveMember(10L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.createGeneralOrder(
                10L,
                form(1L, 1, List.of())
        )).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(OrderErrorCode.MEMBER_NOT_AVAILABLE)
        );

        verify(productQueryService, never()).getSalesInfo(anyLong());
        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    @Test
    void createGeneralOrderRejectsCustomProductBeforeSaving() {
        when(productQueryService.getSalesInfo(2L))
                .thenReturn(product(
                        2L,
                        ProductType.CUSTOM,
                        "주문 제작 케이크",
                        50_000
                ));
        OrderGeneralCreateForm form = form(2L, 1, List.of());

        assertThatThrownBy(() -> orderService.createGeneralOrder(10L, form))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(OrderErrorCode.GENERAL_PRODUCT_REQUIRED)
                );

        verify(orderMapper, never()).insertOrder(any(Order.class));
        verifyNoInteractions(paymentPreparationService);
    }

    @Test
    void createGeneralOrder_inactiveProduct_throwsNotOnSale() {
        when(productQueryService.getSalesInfo(1L))
                .thenThrow(new BusinessException(ProductErrorCode.NOT_ON_SALE));

        assertThatThrownBy(() ->
                orderService.createGeneralOrder(
                        10L,
                        form(1L, 1, List.of())
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(ProductErrorCode.NOT_ON_SALE)
        );

        verify(orderOptionValidator, never()).validate(1L, List.of());
        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    @Test
    void createGeneralOrder_outOfStockProduct_throwsInsufficientStock() {
        when(productQueryService.getSalesInfo(1L))
                .thenReturn(product(
                        1L,
                        ProductType.GENERAL,
                        "품절 케이크",
                        30_000,
                        0
                ));

        assertThatThrownBy(() ->
                orderService.createGeneralOrder(
                        10L,
                        form(1L, 1, List.of())
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK)
        );

        verify(orderOptionValidator, never()).validate(1L, List.of());
        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    @Test
    void createGeneralOrder_quantityExceedsStock_throwsInsufficientStock() {
        when(productQueryService.getSalesInfo(1L))
                .thenReturn(product(
                        1L,
                        ProductType.GENERAL,
                        "재고 제한 케이크",
                        30_000,
                        2
                ));

        assertThatThrownBy(() ->
                orderService.createGeneralOrder(
                        10L,
                        form(1L, 3, List.of())
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK)
        );

        verify(orderOptionValidator, never()).validate(1L, List.of());
        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    @Test
    void createGeneralOrder_amountExceedsDatabaseLimit_throwsOrderAmountExceeded() {
        when(productQueryService.getSalesInfo(1L))
                .thenReturn(product(
                        1L,
                        ProductType.GENERAL,
                        "고액 케이크",
                        600_000_000_000L
                ));
        when(orderOptionValidator.validate(1L, List.of())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.createGeneralOrder(
                10L,
                form(1L, 2, List.of())
        )).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_AMOUNT_EXCEEDED)
        );

        verify(orderMapper, never()).insertOrder(any(Order.class));
        verify(paymentPreparationService, never()).prepareReadyPayment(anyLong(), any(), any());
    }

    @Test
    void createGeneralOrder_pickupAtCurrentTime_throwsInvalidInput() {
        OrderGeneralCreateForm form = form(1L, 1, List.of());
        form.setPickupAt(FIXED_NOW);

        assertThatThrownBy(() ->
                orderService.createGeneralOrder(10L, form)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_INPUT)
        );

        verify(productQueryService, never()).getSalesInfo(1L);
        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    @Test
    void createGeneralOrder_pickupBeforePaymentExpiration_throwsInvalidInput() {
        lenient().when(storeService.getStoreView())
                .thenReturn(storeView(Set.of(), List.of(), 5));
        OrderGeneralCreateForm form = form(1L, 1, List.of());
        form.setPickupAt(FIXED_NOW.plusMinutes(5));

        assertThatThrownBy(() -> orderService.createGeneralOrder(10L, form))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(CommonErrorCode.INVALID_INPUT)
                );

        verify(productQueryService, never()).getSalesInfo(1L);
        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    @Test
    void createGeneralOrder_optionValidationFails_doesNotSaveOrder() {
        when(productQueryService.getSalesInfo(1L))
                .thenReturn(product(
                        1L,
                        ProductType.GENERAL,
                        "딸기 케이크",
                        30_000
                ));
        when(orderOptionValidator.validate(1L, List.of(999L)))
                .thenThrow(new BusinessException(
                        OrderErrorCode.INVALID_PRODUCT_OPTION
                ));

        assertThatThrownBy(() ->
                orderService.createGeneralOrder(
                        10L,
                        form(1L, 1, List.of(999L))
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(OrderErrorCode.INVALID_PRODUCT_OPTION)
        );

        verify(orderMapper, never()).insertOrder(any(Order.class));
        verifyNoInteractions(paymentPreparationService);
    }

    @Test
    void createGeneralOrderIsTransactional() throws NoSuchMethodException {
        Transactional transactional = OrderService.class
                .getMethod(
                        "createGeneralOrder",
                        long.class,
                        OrderGeneralCreateForm.class
                )
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
    }

    @Test
    void createGeneralOrder_unlimitedStockOverMaximumQuantity_returnsInvalidQuantity() {
        when(productQueryService.getSalesInfo(1L))
                .thenReturn(product(1L, ProductType.GENERAL, "무제한 케이크", 30_000));

        assertThatThrownBy(() -> orderService.createGeneralOrder(
                10L,
                form(1L, 11, List.of())
        )).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(OrderErrorCode.INVALID_QUANTITY)
        );

        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    private ProductSalesInfo product(
            long id,
            ProductType productType,
            String name,
            long basePrice
    ) {
        return product(id, productType, name, basePrice, null);
    }

    private ProductSalesInfo product(
            long id,
            ProductType productType,
            String name,
            long basePrice,
            Integer stockQuantity
    ) {
        return new ProductSalesInfo(
                id,
                name,
                productType,
                0,
                stockQuantity == null || stockQuantity > 0,
                BigDecimal.valueOf(basePrice),
                stockQuantity
                );
    }

    @Test
    void createGeneralOrder_zeroAmount_throwsInvalidOrderAmountWithoutSaving() {
        when(productQueryService.getSalesInfo(1L))
                .thenReturn(product(1L, ProductType.GENERAL, "무료 케이크", 0, 2));
        when(orderOptionValidator.validate(1L, List.of())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.createGeneralOrder(10L, form(1L, 1, List.of())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_ORDER_AMOUNT)
                );

        verify(orderMapper, never()).insertOrder(any(Order.class));
    }

    private OrderGeneralCreateForm form(
            long productId,
            int quantity,
            List<Long> optionIds
    ) {
        OrderGeneralCreateForm form = new OrderGeneralCreateForm();
        form.setRequestKey(UUID.randomUUID().toString());
        form.setOrdererName(" 주문자 ");
        form.setOrdererPhone(" 010-1111-2222 ");
        form.setPickupName(" 수령자 ");
        form.setPickupPhone(" 010-3333-4444 ");
        form.setPickupAt(FIXED_NOW.plusDays(3));
        form.setRequestMessage(" 초는 빼주세요. ");
        form.setProductId(productId);
        form.setQuantity(quantity);
        form.setOptionIds(optionIds);
        return form;
    }

    private OrderCartCreateForm cartForm() {
        OrderCartCreateForm form = new OrderCartCreateForm();
        form.setRequestKey(UUID.randomUUID().toString());
        form.setCartItemIds(List.of(11L));
        form.setOrdererName("주문자");
        form.setOrdererPhone("010-1234-5678");
        form.setPickupName("픽업자");
        form.setPickupPhone("010-9876-5432");
        form.setPickupAt(FIXED_NOW.plusDays(3));
        form.setDisplayedOriginalAmount(BigDecimal.valueOf(10_000));
        return form;
    }

    private Order pendingPaymentOrder(long orderId, String orderNumber, LocalDateTime paymentExpiresAt) {
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNumber(orderNumber);
        order.setPaymentExpiresAt(paymentExpiresAt);
        return order;
    }

    private StoreView storeView() {
        return storeView(Set.of(), List.of());
    }

    private StoreView storeView(
            Set<DayOfWeek> closedDays,
            List<StoreHoliday> holidays
    ) {
        return storeView(closedDays, holidays, 60);
    }

    private StoreView storeView(
            Set<DayOfWeek> closedDays,
            List<StoreHoliday> holidays,
            int pickupIntervalMinutes
    ) {
        return new StoreView(
                1L,
                "테스트 매장",
                null,
                null,
                "서울시",
                "02-0000-0000",
                LocalTime.of(9, 0),
                LocalTime.of(20, 0),
                LocalTime.of(9, 0),
                LocalTime.of(20, 0),
                closedDays,
                "1층",
                LocalTime.of(10, 0),
                LocalTime.of(19, 0),
                pickupIntervalMinutes,
                holidays
        );
    }
}
