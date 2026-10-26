package com.cakeshop.domain.order.service;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy;
import com.cakeshop.domain.order.service.payment.OrderExpirationService;
import com.cakeshop.domain.order.service.payment.OrderPaymentCommandService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentExecutionOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentProduct;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.infra.ApprovalResult;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.service.PaymentOrderPreparationCommandService;
import com.cakeshop.domain.payment.service.PaymentRecoveryService;
import com.cakeshop.domain.payment.service.PaymentService;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@MybatisTest
@Import({
        PaymentService.class,
        ProductStockService.class,
        OrderPaymentCommandService.class,
        OrderService.class,
        PickupAvailabilityPolicy.class,
        OrderExpirationService.class
})
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderPaymentExpirationConcurrencyTests {

    @MockitoBean
    private CouponOrderCommandService couponOrderCommandService;

    @MockitoBean
    private CartOrderQueryService cartOrderQueryService;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 10, 0);

    private final PaymentService paymentService;
    private final OrderExpirationService expirationService;
    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @MockitoBean
    private ProductQueryService productQueryService;

    @MockitoBean
    private OrderOptionValidator orderOptionValidator;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private PaymentOrderPreparationCommandService paymentPreparationService;

    @MockitoBean
    private PaymentRecoveryService paymentRecoveryService;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private MemberCouponQueryService memberCouponQueryService;

    @MockitoBean
    private Clock clock;

    private long orderId;
    private long orderItemId;
    private long productId;
    private Payment readyPayment;
    private String suffix;

    @Autowired
    OrderPaymentExpirationConcurrencyTests(
            PaymentService paymentService,
            OrderExpirationService expirationService,
            PaymentMapper paymentMapper,
            OrderMapper orderMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.paymentService = paymentService;
        this.expirationService = expirationService;
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW.atZone(SEOUL).toInstant());
        when(clock.getZone()).thenReturn(SEOUL);
        suffix = Long.toString(System.nanoTime());
        long memberId = insertMember();
        productId = insertProduct();
        orderId = insertOrder(memberId);
        orderItemId = insertOrderItem();
        readyPayment = insertReadyPayment();
    }

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void customPaymentCompletionAndExpiration_concurrentExecution_keepsOneConsistentResult()
            throws Exception {
        jdbcTemplate.update(
                "UPDATE products SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?",
                productId
        );
        jdbcTemplate.update("UPDATE orders SET order_type = 'CUSTOM' WHERE id = ?", orderId);
        jdbcTemplate.update(
                "UPDATE order_items SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?",
                orderItemId
        );
        CountDownLatch start = new CountDownLatch(1);
        Future<?> completion = executor.submit(() -> {
            await(start);
            try {
                paymentService.completePayment(
                        new PaymentExecutionOrder(
                                orderId,
                                OrderType.CUSTOM,
                                BigDecimal.valueOf(40_000),
                                NOW,
                                List.of(new PaymentProduct(orderItemId, productId, 2))
                        ),
                        readyPayment,
                        new ApprovalResult(
                                "RACE-PAYMENT-KEY-" + suffix,
                                readyPayment.getTossOrderId(),
                                "CARD",
                                PaymentGatewayStatus.DONE,
                                40_000,
                                NOW
                        )
                );
            } catch (RuntimeException ignored) {
                // 만료 트랜잭션이 먼저 확정되면 결제 완료는 상태 검증에서 거부된다.
            }
        });
        Future<?> expiration = executor.submit(() -> {
            await(start);
            expirationService.expireOverdueOrders();
        });

        start.countDown();
        completion.get();
        expiration.get();

        OrderStatus orderStatus = orderMapper.findOrderById(orderId)
                .orElseThrow()
                .getStatus();
        PaymentStatus paymentStatus = paymentMapper.findPaymentById(readyPayment.getId())
                .orElseThrow()
                .getStatus();
        int stock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class,
                productId
        );

        if (orderStatus == OrderStatus.UNDER_REVIEW) {
            assertThat(paymentStatus).isEqualTo(PaymentStatus.DONE);
            assertThat(stock).isEqualTo(3);
        } else {
            assertThat(orderStatus).isEqualTo(OrderStatus.EXPIRED);
            assertThat(paymentStatus).isEqualTo(PaymentStatus.EXPIRED);
            assertThat(stock).isEqualTo(5);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private long insertMember() {
        String email = "expiration-race-" + suffix + "@example.com";
        jdbcTemplate.update(
                """
                INSERT INTO members (email, name, nickname, phone, role, status)
                VALUES (?, '경합 회원', '경합회원', '010-0000-0000', 'USER', 'ACTIVE')
                """,
                email
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    private long insertProduct() {
        String categoryCode = "EXPIRATION_RACE_" + suffix;
        jdbcTemplate.update(
                "INSERT INTO categories (code, name, sort_order, is_active) VALUES (?, ?, 999, 1)",
                categoryCode,
                "만료 경합"
        );
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE code = ?",
                Long.class,
                categoryCode
        );
        String productName = "만료 경합 상품 " + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    category_id, name, description, base_price, product_type,
                    preparation_days, stock_quantity, status
                ) VALUES (?, ?, '', 20000, 'GENERAL', 0, 5, 'ACTIVE')
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

    private long insertOrder(long memberId) {
        String orderNumber = "EXPIRATION-RACE-" + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    order_number, member_id, order_type, orderer_name, orderer_phone,
                    pickup_name, pickup_phone, original_amount, discount_amount,
                    final_amount, status, pickup_at, payment_expires_at
                ) VALUES (?, ?, 'GENERAL', '주문자', '010-1111-2222', '수령자',
                          '010-3333-4444', 40000, 0, 40000, 'PENDING_PAYMENT',
                          ?, ?)
                """,
                orderNumber,
                memberId,
                NOW.plusDays(1),
                NOW.minusSeconds(1)
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?",
                Long.class,
                orderNumber
        );
    }

    private long insertOrderItem() {
        jdbcTemplate.update(
                """
                INSERT INTO order_items (
                    order_id, product_id, product_name, product_type, quantity,
                    base_price, option_amount, total_amount, preparation_days
                ) VALUES (?, ?, ?, 'GENERAL', 2, 20000, 0, 40000, 0)
                """,
                orderId,
                productId,
                "만료 경합 상품 " + suffix
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ?",
                Long.class,
                orderId
        );
    }

    private Payment insertReadyPayment() {
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setTossOrderId("TOSS-RACE-" + suffix);
        payment.setIdempotencyKey("IDEMPOTENCY-RACE-" + suffix);
        payment.setAmount(BigDecimal.valueOf(40_000));
        assertThat(paymentMapper.insertReadyPayment(payment)).isEqualTo(1);
        return paymentMapper.findPaymentById(payment.getId()).orElseThrow();
    }
}
