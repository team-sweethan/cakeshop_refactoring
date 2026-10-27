package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.cart.dto.view.CartOrderItemView;
import com.cakeshop.domain.order.dto.form.customer.OrderCartCreateForm;
import com.cakeshop.domain.order.dto.form.customer.OrderCustomCreateForm;
import com.cakeshop.domain.order.dto.form.customer.OrderCreateForm;
import com.cakeshop.domain.order.service.customer.CustomerCustomOrderService;
import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.dto.form.customer.OrderGeneralCreateForm;
import com.cakeshop.domain.order.dto.view.customer.OrderCreationResult;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.service.PaymentOrderPreparationCommandServiceImpl;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@MybatisTest
@Import({
        OrderService.class,
        CustomerCustomOrderService.class,
        MemberCouponQueryService.class,
        PaymentOrderPreparationCommandServiceImpl.class
})
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderPendingPaymentConcurrencyTests {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 11, 0);

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @MockitoBean private com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy pickupAvailabilityPolicy;
    @MockitoBean private ProductQueryService productQueryService;
    @MockitoBean private com.cakeshop.domain.order.service.checkout.OrderOptionValidator orderOptionValidator;
    @MockitoBean private MemberService memberService;
    @MockitoBean private CouponOrderCommandService couponOrderCommandService;
    @MockitoBean private CartOrderQueryService cartOrderQueryService;
    @MockitoBean private Clock clock;

    @Autowired
    private CustomerCustomOrderService customOrderService;

    private long categoryId;
    private long memberId;
    private long productId;

    @Autowired
    OrderPendingPaymentConcurrencyTests(
            OrderService orderService,
            OrderMapper orderMapper,
            PaymentMapper paymentMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        memberId = insertActiveMember();
        productId = insertProduct();
        when(memberService.isActiveMember(memberId)).thenReturn(true);
        when(clock.instant()).thenReturn(NOW.atZone(SEOUL).toInstant());
        when(clock.getZone()).thenReturn(SEOUL);
        when(pickupAvailabilityPolicy.isAvailable(any())).thenReturn(true);
        when(productQueryService.getSalesInfo(productId)).thenReturn(new ProductSalesInfo(
                productId,
                "동시성 테스트 케이크",
                ProductType.GENERAL,
                0,
                true,
                BigDecimal.valueOf(30_000),
                10
        ));
        when(orderOptionValidator.validate(productId, List.of())).thenReturn(List.of());
    }

    @AfterEach
    void shutDownExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE member_id = ?)", memberId);
        jdbcTemplate.update("DELETE FROM order_cart_items WHERE order_id IN (SELECT id FROM orders WHERE member_id = ?)", memberId);
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE member_id = ?)", memberId);
        jdbcTemplate.update("DELETE FROM orders WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM members WHERE id = ?", memberId);
    }

    @Test
    void createGeneralOrder_differentRequestKeysConcurrently_createsOneReadyPaymentAndOneGuide()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<OrderCreationResult> first = executor.submit(() -> {
            await(start);
            return orderService.createGeneralOrder(memberId, form());
        });
        Future<OrderCreationResult> second = executor.submit(() -> {
            await(start);
            return orderService.createGeneralOrder(memberId, form());
        });

        start.countDown();
        List<OrderCreationResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertThat(results).filteredOn(OrderCreationResult::requiresPendingPaymentGuide).hasSize(1);
        assertThat(results).filteredOn(result -> !result.requiresPendingPaymentGuide()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE member_id = ?",
                Long.class,
                memberId
        )).isEqualTo(1L);
        long createdOrderId = results.stream()
                .filter(result -> !result.requiresPendingPaymentGuide())
                .findFirst()
                .orElseThrow()
                .orderId();
        assertThat(paymentMapper.findReadyPaymentByOrderId(createdOrderId)).isPresent();
        assertThat(orderMapper.findOrderById(
                results.stream()
                        .filter(OrderCreationResult::requiresPendingPaymentGuide)
                        .findFirst()
                        .orElseThrow()
                        .orderId()
        )).isPresent();
    }

    @ParameterizedTest
    @EnumSource(Checkout.class)
    void createOrder_sameRequestKeyConcurrentlyAndReplay_reusesOneOrderAndPayment(Checkout checkout)
            throws Exception {
        String requestKey = UUID.randomUUID().toString();
        Supplier<OrderCreationResult> create;
        if (checkout == Checkout.CUSTOM) {
            jdbcTemplate.update("UPDATE products SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?", productId);
            when(productQueryService.getSalesInfo(productId)).thenReturn(new ProductSalesInfo(
                    productId, "수제 경합 케이크", ProductType.CUSTOM, 1, true, BigDecimal.valueOf(30_000), 10));
            OrderCustomCreateForm custom = new OrderCustomCreateForm();
            populate(custom, requestKey);
            custom.setProductId(productId);
            custom.setOptionIds(List.of());
            create = () -> customOrderService.createCustomOrder(memberId, custom);
        } else if (checkout == Checkout.CART) {
            OrderCartCreateForm cart = new OrderCartCreateForm();
            populate(cart, requestKey);
            cart.setCartItemIds(List.of(91L));
            when(cartOrderQueryService.getSelectedOrderItems(memberId, List.of(91L)))
                    .thenReturn(List.of(new CartOrderItemView(91L, productId, 1, null, List.of())));
            create = () -> orderService.createCartOrder(memberId, cart);
        } else {
            OrderGeneralCreateForm general = form();
            general.setRequestKey(requestKey);
            create = () -> orderService.createGeneralOrder(memberId, general);
        }
        CountDownLatch start = new CountDownLatch(1);
        Future<OrderCreationResult> first = executor.submit(() -> { await(start); return create.get(); });
        Future<OrderCreationResult> second = executor.submit(() -> { await(start); return create.get(); });
        start.countDown();
        OrderCreationResult result = first.get(10, TimeUnit.SECONDS);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(result);
        assertThat(create.get()).isEqualTo(result);
        assertThat(result.requiresPendingPaymentGuide()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE member_id = ?", Integer.class, memberId))
                .isEqualTo(1);
        assertThat(orderMapper.findOrderItemsByOrderId(result.orderId())).hasSize(1);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM payments WHERE order_id = ?", String.class, result.orderId()))
                .containsExactly("READY");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_cart_items WHERE order_id = ?", Integer.class, result.orderId()))
                .isEqualTo(checkout == Checkout.CART ? 1 : 0);
        assertThat(jdbcTemplate.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(10);
    }

    private void populate(OrderCreateForm form, String requestKey) {
        form.setRequestKey(requestKey);
        form.setOrdererName("주문자");
        form.setOrdererPhone("010-1111-2222");
        form.setPickupName("픽업자");
        form.setPickupPhone("010-3333-4444");
        form.setPickupAt(NOW.plusDays(3));
        form.setDisplayedOriginalAmount(BigDecimal.valueOf(30_000));
    }

    enum Checkout { GENERAL, CART, CUSTOM }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private long insertActiveMember() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "pending-payment-race-" + suffix + "@example.com";
        jdbcTemplate.update(
                """
                INSERT INTO members (email, name, nickname, phone, role, status)
                VALUES (?, '경합 회원', ?, '010-0000-0000', 'USER', 'ACTIVE')
                """,
                email,
                "경합회원-" + suffix
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    private long insertProduct() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String categoryCode = "PENDING_PAYMENT_RACE_" + suffix;
        jdbcTemplate.update(
                "INSERT INTO categories (code, name, sort_order, is_active) VALUES (?, ?, 999, 1)",
                categoryCode,
                "미결제 주문 경합"
        );
        categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE code = ?",
                Long.class,
                categoryCode
        );
        String productName = "미결제 주문 경합 케이크 " + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    category_id, name, description, base_price, product_type,
                    preparation_days, stock_quantity, status
                ) VALUES (?, ?, '', 30000, 'GENERAL', 0, 10, 'ACTIVE')
                """,
                categoryId,
                productName
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE name = ?",
                Long.class,
                productName
        );
    }

    private OrderGeneralCreateForm form() {
        OrderGeneralCreateForm form = new OrderGeneralCreateForm();
        form.setRequestKey(UUID.randomUUID().toString());
        form.setOrdererName("주문자");
        form.setOrdererPhone("010-1111-2222");
        form.setPickupName("픽업자");
        form.setPickupPhone("010-3333-4444");
        form.setPickupAt(NOW.plusDays(3));
        form.setDisplayedOriginalAmount(BigDecimal.valueOf(30_000));
        form.setProductId(productId);
        form.setQuantity(1);
        form.setOptionIds(List.of());
        return form;
    }
}
