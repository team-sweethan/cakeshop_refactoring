package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.member.service.MemberOrderQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.admin.AdminCustomOrderService;
import com.cakeshop.domain.order.service.payment.OrderPaymentCancellationCommandService;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.service.RefundService;
import com.cakeshop.domain.payment.service.RefundService.RefundRequest;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import com.cakeshop.global.error.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@MybatisTest
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminCustomOrderService.class, RefundService.class,
        OrderPaymentCancellationCommandService.class, ProductStockService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomOrderCancellationConcurrencyTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 0);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private AdminCustomOrderService adminService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private MemberService memberService;
    @MockitoBean
    private MemberOrderQueryService memberOrderQueryService;
    @MockitoBean
    private CouponOrderCommandService couponService;
    @MockitoBean
    private Clock clock;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private long memberId;
    private long adminId;
    private long categoryId;
    private long productId;
    private long orderId;
    private String suffix;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW.atZone(SEOUL).toInstant());
        when(clock.getZone()).thenReturn(SEOUL);
        suffix = UUID.randomUUID().toString().replace("-", "");
        memberId = insertMember("USER");
        adminId = insertMember("ADMIN");
        when(memberService.isActiveMember(memberId)).thenReturn(true);
        when(memberOrderQueryService.isActiveAdmin(adminId)).thenReturn(true);
        jdbc.update("INSERT INTO categories (code, name, sort_order, is_active) VALUES (?, '경합', 999, 1)", suffix);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE code = ?", Long.class, suffix);
        jdbc.update("""
                INSERT INTO products (category_id, name, description, base_price, product_type,
                    preparation_days, stock_quantity, status)
                VALUES (?, ?, '', 40000, 'CUSTOM', 1, 4, 'ACTIVE')
                """, categoryId, suffix);
        productId = jdbc.queryForObject("SELECT id FROM products WHERE name = ?", Long.class, suffix);
        jdbc.update("""
                INSERT INTO orders (order_number, member_id, order_type, orderer_name, orderer_phone,
                    pickup_name, pickup_phone, original_amount, discount_amount, final_amount,
                    status, pickup_at, payment_expires_at)
                VALUES (?, ?, 'CUSTOM', '주문자', '010-1111-2222', '수령자', '010-3333-4444',
                    40000, 0, 40000, 'UNDER_REVIEW', ?, ?)
                """, suffix, memberId, NOW.plusDays(3), NOW.minusMinutes(1));
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, suffix);
        jdbc.update("""
                INSERT INTO order_items (order_id, product_id, product_name, product_type, quantity,
                    base_price, option_amount, total_amount, preparation_days, stock_deducted_at)
                VALUES (?, ?, '경합 상품', 'CUSTOM', 1, 40000, 0, 40000, 1, ?)
                """, orderId, productId, NOW.minusMinutes(2));
        jdbc.update("""
                INSERT INTO payments (order_id, toss_order_id, payment_key, idempotency_key,
                    amount, method, status, approved_at)
                VALUES (?, ?, ?, ?, 40000, 'CARD', 'DONE', ?)
                """, orderId, suffix, "KEY-" + suffix, "PAY-" + suffix, NOW.minusMinutes(2));
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM payment_cancellations WHERE payment_id IN (SELECT id FROM payments WHERE order_id = ?)", orderId);
        jdbc.update("DELETE FROM payments WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM order_items WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM members WHERE id IN (?, ?)", memberId, adminId);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CUSTOMER", "REJECTION"})
    void startProduction_refundWaitingForGateway_rejectsApprovalAndCompletesRefund(Action action) throws Exception {
        RefundRequest request = prepare(action);
        // 재처리 대상인 PG 응답 지연 요청도 최신 주문 상태로 조회할 수 있어야 한다.
        jdbc.update("""
                UPDATE payment_cancellations
                SET requested_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE)
                WHERE id = ?
                """, request.cancellationId());
        assertThat(refundService.getRequestedCancellations(10))
                .extracting(RefundRequest::cancellationId).containsExactly(request.cancellationId());
        // 환불 준비는 커밋됐고 외부 PG 응답은 아직 도착하지 않은 시점이다.
        BusinessException approval = executor.submit(() -> attempt(Action.APPROVAL)).get(10, TimeUnit.SECONDS);
        assertThat(approval).isNotNull();
        assertThat(approval.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_STATUS_TRANSITION);
        completeTwiceConcurrently(request);
        assertRefunded(action);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void competingActions_snapshotBeforeOrderLock_preservesFirstResult(Action first) throws Exception {
        CountDownLatch contendersHaveSnapshot = new CountDownLatch(2);
        Map<Action, Future<BusinessException>> contenders = new EnumMap<>(Action.class);
        RefundRequest request = transaction().execute(status -> {
            orderMapper.findOrderByIdForUpdate(orderId).orElseThrow();
            RefundRequest prepared = first == Action.APPROVAL ? approve() : prepare(first);
            for (Action action : Action.values()) {
                if (action != first) {
                    contenders.put(action, executor.submit(() -> attemptWithSnapshot(action, contendersHaveSnapshot)));
                }
            }
            await(contendersHaveSnapshot);
            return prepared;
        });
        for (var contender : contenders.entrySet()) {
            BusinessException failure = contender.getValue().get(10, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(failure.getErrorCode()).isEqualTo(contender.getKey() == Action.APPROVAL
                    ? OrderErrorCode.INVALID_STATUS_TRANSITION
                    : PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE);
        }
        if (first == Action.APPROVAL) {
            assertThat(orderMapper.findOrderById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.IN_PRODUCTION);
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId))
                    .isEqualTo("DONE");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_cancellations
                    WHERE payment_id IN (SELECT id FROM payments WHERE order_id = ?)
                    """, Integer.class, orderId)).isZero();
            assertStock(4, 0);
        } else {
            completeTwiceConcurrently(request);
            assertRefunded(first);
        }
    }

    private BusinessException attemptWithSnapshot(Action action, CountDownLatch snapshotReady) {
        try {
            transaction().executeWithoutResult(status -> {
                // 실제 인증 조회처럼 잠금 대기 전에 REPEATABLE_READ 스냅샷이 생긴 요청이다.
                jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
                snapshotReady.countDown();
                if (action == Action.APPROVAL) {
                    approve();
                } else {
                    prepare(action);
                }
            });
            return null;
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private RefundRequest approve() {
        adminService.startProduction(orderId, adminId);
        return null;
    }

    private RefundRequest prepare(Action action) {
        return action == Action.CUSTOMER
                ? refundService.prepareCustomerCancellation(memberId, orderId, "고객 취소")
                : refundService.prepareAdminRejection(adminId, orderId, "관리자 반려");
    }

    private BusinessException attempt(Action action) {
        try {
            if (action == Action.APPROVAL) {
                approve();
            } else {
                prepare(action);
            }
            return null;
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private void completeTwiceConcurrently(RefundRequest request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CancellationResult result = new CancellationResult(PaymentGatewayStatus.CANCELED, "TX-" + suffix, NOW);
        Runnable complete = () -> {
            await(start);
            refundService.completeCancellation(request, result);
        };
        Future<?> first = executor.submit(complete);
        Future<?> second = executor.submit(complete);
        start.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
    }

    private void assertRefunded(Action action) {
        assertThat(orderMapper.findOrderById(orderId).orElseThrow().getStatus())
                .isEqualTo(action == Action.CUSTOMER ? OrderStatus.CANCELED : OrderStatus.REJECTED);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForList("SELECT status FROM payment_cancellations WHERE payment_id IN (SELECT id FROM payments WHERE order_id = ?)", String.class, orderId))
                .containsExactly("DONE");
        assertStock(5, 1);
    }

    private void assertStock(int stock, int restoredItems) {
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId)).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ? AND stock_restored_at IS NOT NULL", Integer.class, orderId)).isEqualTo(restoredItems);
    }

    private long insertMember(String role) {
        String email = role + suffix + "@example.com";
        jdbc.update("INSERT INTO members (email, name, nickname, phone, role, status) VALUES (?, '경합', ?, '010-0000-0000', ?, 'ACTIVE')", email, role + suffix, role);
        return jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    enum Action { CUSTOMER, REJECTION, APPROVAL }
}
