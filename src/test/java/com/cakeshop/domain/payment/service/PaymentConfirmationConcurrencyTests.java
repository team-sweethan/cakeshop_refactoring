package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.service.OrderViewAssembler;
import com.cakeshop.domain.order.service.customer.OrderCustomerService;
import com.cakeshop.domain.order.service.payment.OrderPaymentCommandService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentRecoveryService;
import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@MybatisTest
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentFacade.class, PaymentService.class, PaymentRecoveryService.class,
        PaymentCompensationProcessor.class, PaymentApprovalResolver.class,
        OrderPaymentCommandService.class, OrderPaymentRecoveryService.class,
        OrderPaymentQueryService.class, OrderCustomerService.class, OrderViewAssembler.class,
        ProductStockService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConfirmationConcurrencyTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 11, 0);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired private PaymentFacade facade;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentMapper paymentMapper;
    @MockitoBean private MemberService memberService;
    @MockitoBean private CouponOrderCommandService couponService;
    @MockitoBean private Clock clock;
    @MockitoBean private PaymentGateway gateway;
    @MockitoBean private PaymentGatewayRouter gatewayRouter;
    @MockitoSpyBean private OrderPaymentQueryService orderQuery;
    @MockitoSpyBean private PaymentService paymentService;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private long memberId;
    private long categoryId;
    private long productId;
    private long orderId;
    private String orderNumber;
    private String paymentKey;
    private Payment payment;

    @BeforeEach
    void setUp() {
        when(gatewayRouter.forProvider(PaymentProvider.TOSS)).thenReturn(gateway);
        when(clock.instant()).thenReturn(NOW.atZone(SEOUL).toInstant());
        when(clock.getZone()).thenReturn(SEOUL);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        orderNumber = "ORD-" + suffix;
        paymentKey = "KEY-" + suffix;
        jdbc.update("""
                INSERT INTO members (email, name, nickname, phone, role, status)
                VALUES (?, '결제 경합', ?, '010-0000-0000', 'USER', 'ACTIVE')
                """, suffix + "@example.com", suffix);
        memberId = jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, suffix + "@example.com");
        when(memberService.isActiveMember(memberId)).thenReturn(true);
        jdbc.update("INSERT INTO categories (code, name, sort_order, is_active) VALUES (?, '결제 경합', 999, 1)", suffix);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE code = ?", Long.class, suffix);
        jdbc.update("""
                INSERT INTO products (category_id, name, description, base_price, product_type,
                    preparation_days, stock_quantity, status)
                VALUES (?, ?, '', 30000, 'GENERAL', 0, 5, 'ACTIVE')
                """, categoryId, suffix);
        productId = jdbc.queryForObject("SELECT id FROM products WHERE name = ?", Long.class, suffix);
        jdbc.update("""
                INSERT INTO orders (order_number, member_id, order_type, orderer_name, orderer_phone,
                    pickup_name, pickup_phone, original_amount, discount_amount, final_amount,
                    status, pickup_at, payment_expires_at)
                VALUES (?, ?, 'GENERAL', '주문자', '010-1111-2222', '수령자', '010-3333-4444',
                    30000, 0, 30000, 'PENDING_PAYMENT', ?, ?)
                """, orderNumber, memberId, NOW.plusDays(3), NOW.plusMinutes(10));
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, orderNumber);
        jdbc.update("""
                INSERT INTO order_items (order_id, product_id, product_name, product_type, quantity,
                    base_price, option_amount, total_amount, preparation_days)
                VALUES (?, ?, '결제 경합', 'GENERAL', 1, 30000, 0, 30000, 0)
                """, orderId, productId);
        payment = new Payment();
        payment.setProvider(PaymentProvider.TOSS);
        payment.setOrderId(orderId);
        payment.setGatewayOrderId(orderNumber);
        payment.setIdempotencyKey("PAY-" + suffix);
        payment.setAmount(BigDecimal.valueOf(30_000));
        assertThat(paymentMapper.insertReadyPayment(payment)).isEqualTo(1);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM payment_cancellations WHERE payment_id = ?", payment.getId());
        jdbc.update("DELETE FROM payments WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM order_items WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM members WHERE id = ?", memberId);
    }

    @ParameterizedTest
    @CsvSource({"GENERAL, false", "CUSTOM, false", "GENERAL, true", "CUSTOM, true"})
    void confirmPayment_concurrentCallbacksAndReplay_completesOnceWithoutCompensation(
            OrderType type, boolean alreadyApproved) throws Exception {
        if (type == OrderType.CUSTOM) {
            jdbc.update("UPDATE products SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?", productId);
            jdbc.update("UPDATE orders SET order_type = 'CUSTOM' WHERE id = ?", orderId);
            jdbc.update("UPDATE order_items SET product_type = 'CUSTOM', preparation_days = 1 WHERE order_id = ?", orderId);
        }
        PaymentLookupResult done = new PaymentLookupResult(paymentKey, orderNumber, "CARD",
                PaymentGatewayStatus.DONE, 30_000, NOW, null);
        AtomicReference<PaymentLookupResult> providerState = new AtomicReference<>(alreadyApproved ? done : null);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger charges = new AtomicInteger();
        CyclicBarrier firstLookups = new CyclicBarrier(2);
        when(gateway.find(paymentKey)).thenAnswer(invocation -> {
            PaymentLookupResult current = providerState.get();
            if (lookups.incrementAndGet() <= 2) {
                firstLookups.await(10, TimeUnit.SECONDS);
            }
            return Optional.ofNullable(current);
        });
        when(gateway.approve(anyString(), anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, String.class)).isEqualTo(paymentKey);
            assertThat(invocation.getArgument(1, String.class)).isEqualTo(orderNumber);
            assertThat(invocation.getArgument(2, Long.class)).isEqualTo(30_000);
            assertThat(invocation.getArgument(3, String.class)).isEqualTo(payment.getIdempotencyKey());
            if (providerState.compareAndSet(null, done)) {
                charges.incrementAndGet();
            }
            return done.toApprovalResult();
        });
        List<BusinessException> outcomes = race(() -> facade.confirmPayment(memberId, orderId, form()));
        // 다른 요청이 승인 보호 요청을 선점하면 일시 대기를 허용하되, 재전송은 반드시 성공해야 한다.
        for (BusinessException outcome : outcomes) {
            if (outcome != null) {
                assertThat(outcome.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
            }
        }
        facade.confirmPayment(memberId, orderId, form());
        assertCompleted(type == OrderType.CUSTOM ? "UNDER_REVIEW" : "READY_FOR_PICKUP");
        assertThat(charges.get()).isEqualTo(alreadyApproved ? 0 : 1);
        verify(gateway, never()).cancel(anyString(), anyString(), anyString());
        if (alreadyApproved) {
            verify(gateway, never()).approve(anyString(), anyString(), anyLong(), anyString());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_cancellations WHERE payment_id = ? AND status = 'REQUESTED'", Integer.class, payment.getId()))
                .isZero();
    }

    @Test
    void completeZeroAmountGeneralPayment_concurrentCallbacksAndReplay_completesOnce() throws Exception {
        jdbc.update("UPDATE orders SET discount_amount = 30000, final_amount = 0 WHERE id = ?", orderId);
        jdbc.update("UPDATE payments SET amount = 0 WHERE id = ?", payment.getId());
        // 두 요청 모두 READY 결제를 읽은 뒤 내부 완료 트랜잭션에 진입한다.
        CyclicBarrier executionReads = new CyclicBarrier(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            executionReads.await(10, TimeUnit.SECONDS);
            return result;
        }).when(paymentService).getReadyPayment(orderId);
        List<BusinessException> outcomes = race(() -> facade.completeZeroAmountGeneralPayment(memberId, orderId));
        assertThat(outcomes).containsOnlyNulls();
        facade.completeZeroAmountGeneralPayment(memberId, orderId);
        assertCompleted("READY_FOR_PICKUP");
        verify(gateway, never()).approve(anyString(), anyString(), anyLong(), anyString());
        verify(gateway, never()).cancel(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(OrderType.class)
    void confirmPayment_anotherCallbackCompletesBeforeExecutionRead_returnsCompletedResult(OrderType type)
            throws Exception {
        if (type == OrderType.CUSTOM) {
            jdbc.update("UPDATE products SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?", productId);
            jdbc.update("UPDATE orders SET order_type = 'CUSTOM' WHERE id = ?", orderId);
            jdbc.update("UPDATE order_items SET product_type = 'CUSTOM', preparation_days = 1 WHERE order_id = ?", orderId);
        }
        when(gateway.find(paymentKey)).thenReturn(Optional.of(new PaymentLookupResult(
                paymentKey, orderNumber, "CARD", PaymentGatewayStatus.DONE, 30_000, NOW, null)));
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                paused.countDown();
                assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(orderQuery).getMemberPaymentExecutionOrder(memberId, orderId);
        Future<?> delayed = executor.submit(() -> facade.confirmPayment(memberId, orderId, form()));
        try {
            assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
            facade.confirmPayment(memberId, orderId, form());
        } finally {
            completed.countDown();
        }
        delayed.get(15, TimeUnit.SECONDS);
        assertCompleted(type == OrderType.CUSTOM ? "UNDER_REVIEW" : "READY_FOR_PICKUP");
        verify(gateway, never()).approve(anyString(), anyString(), anyLong(), anyString());
        verify(gateway, never()).cancel(anyString(), anyString(), anyString());
    }

    private List<BusinessException> race(Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<BusinessException> run = () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                action.run();
                return null;
            } catch (BusinessException exception) {
                return exception;
            }
        };
        Future<BusinessException> first = executor.submit(run);
        Future<BusinessException> second = executor.submit(run);
        start.countDown();
        return java.util.Arrays.asList(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    }

    private PaymentConfirmForm form() {
        return new PaymentConfirmForm(paymentKey, orderNumber, BigDecimal.valueOf(30_000));
    }

    private void assertCompleted(String status) {
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo(status);
        assertThat(jdbc.queryForList("SELECT status FROM payments WHERE order_id = ?", String.class, orderId)).containsExactly("DONE");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ? AND stock_deducted_at IS NOT NULL AND stock_restored_at IS NULL", Integer.class, orderId))
                .isEqualTo(1);
        verify(couponService, org.mockito.Mockito.times(1)).useReservedCouponForOrder(orderId);
    }
}
