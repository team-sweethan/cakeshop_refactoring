package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.member.service.MemberOrderQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.service.payment.OrderPaymentCancellationCommandService;
import com.cakeshop.domain.order.service.payment.OrderPaymentRecoveryService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import com.cakeshop.global.error.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** DB에서 다시 읽은 서로 다른 PG의 요청을 장애·복구 전후에 처리한다. 외부 HTTP 계약은 사용하지 않는다. */
@MybatisTest
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RefundService.class, PaymentRecoveryService.class, ProductStockService.class,
        OrderPaymentCancellationCommandService.class, OrderPaymentRecoveryService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentProviderRecoveryIntegrationTests {

    private LocalDateTime now;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired private RefundService refunds;
    @Autowired private PaymentRecoveryService recovery;
    @Autowired private PaymentMapper payments;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MemberService memberService;
    @MockitoBean private MemberOrderQueryService memberOrderQueryService;
    @MockitoBean private CouponOrderCommandService coupons;
    @MockitoBean private Clock clock;

    private final List<PendingPayment> fixtures = new ArrayList<>();
    private long adminId;
    private long categoryId;
    private String suffix;

    @BeforeEach
    void setUp() {
        now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        when(clock.instant()).thenReturn(now.atZone(SEOUL).toInstant());
        when(clock.getZone()).thenReturn(SEOUL);
        suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO members (email, name, nickname, phone, role, status)
                VALUES (?, '복구 관리자', ?, '010-0000-0000', 'ADMIN', 'ACTIVE')
                """, suffix + "@example.com", suffix);
        adminId = jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, suffix + "@example.com");
        when(memberOrderQueryService.isActiveAdmin(adminId)).thenReturn(true);
        jdbc.update("INSERT INTO categories (code, name, sort_order, is_active) VALUES (?, 'PG 복구', 999, 1)", suffix);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE code = ?", Long.class, suffix);
    }

    @AfterEach
    void cleanUp() {
        for (PendingPayment fixture : fixtures) {
            jdbc.update("DELETE FROM payment_cancellations WHERE payment_id = ?", fixture.payment().getId());
            jdbc.update("DELETE FROM payments WHERE order_id = ?", fixture.payment().getOrderId());
            jdbc.update("DELETE FROM order_items WHERE order_id = ?", fixture.payment().getOrderId());
            jdbc.update("DELETE FROM orders WHERE id = ?", fixture.payment().getOrderId());
            jdbc.update("DELETE FROM products WHERE id = ?", fixture.productId());
        }
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM members WHERE id = ?", adminId);
    }

    @ParameterizedTest
    @CsvSource({"REFUND, MISSING", "REFUND, TIMEOUT", "COMPENSATION, MISSING", "COMPENSATION, TIMEOUT"})
    void recovery_mixedProvidersAfterSelectionRollback_preservesFailedRequestAndCompletesOtherProvider(
            Operation operation, Failure failure) {
        // 실패 PG 요청을 먼저 넣어 같은 배치의 뒤쪽 Toss 처리까지 확인한다.
        PendingPayment own = prepare(PaymentProvider.OWN, operation);
        PendingPayment toss = prepare(PaymentProvider.TOSS, operation);
        PaymentGateway tossGateway = gateway(PaymentProvider.TOSS, toss);
        PaymentGateway ownGateway = gateway(PaymentProvider.OWN, own);
        if (failure == Failure.TIMEOUT) {
            when(ownGateway.find(own.payment().getPaymentKey()))
                    .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED));
            when(ownGateway.cancel(own.payment().getPaymentKey(), own.reason(), own.idempotencyKey()))
                    .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        }
        PaymentGatewayRouter degraded = new PaymentGatewayRouter("toss", failure == Failure.MISSING
                ? List.of(tossGateway) : List.of(tossGateway, ownGateway));
        process(operation, degraded);

        assertState(own, "READY_FOR_PICKUP", "DONE", "REQUESTED", 4);
        assertState(toss, "CANCELED", "CANCELED", "DONE", 5);
        verify(tossGateway, never()).find(own.payment().getPaymentKey());
        verify(tossGateway, never()).cancel(org.mockito.ArgumentMatchers.eq(own.payment().getPaymentKey()), anyString(), anyString());

        // 처리 객체를 다시 만들고 메모리에 보관한 요청 대신 DB에서 다시 읽는다.
        // 신규 선택은 Toss를 유지하고 복구된 OWN 구현체로 같은 요청을 보낸다.
        org.mockito.Mockito.doReturn(Optional.of(lookup(own)))
                .when(ownGateway).find(own.payment().getPaymentKey());
        org.mockito.Mockito.doReturn(result(own)).when(ownGateway)
                .cancel(own.payment().getPaymentKey(), own.reason(), own.idempotencyKey());
        PaymentGatewayRouter restored = new PaymentGatewayRouter("toss", List.of(tossGateway, ownGateway));
        process(operation, restored);
        process(operation, restored);
        assertState(own, "CANCELED", "CANCELED", "DONE", 5);
        assertState(toss, "CANCELED", "CANCELED", "DONE", 5);
        verify(coupons, times(1)).restoreCouponForCanceledOrder(own.payment().getOrderId());
        verify(coupons, times(1)).restoreCouponForCanceledOrder(toss.payment().getOrderId());
        verify(ownGateway, times(failure == Failure.TIMEOUT ? 2 : 1))
                .cancel(own.payment().getPaymentKey(), own.reason(), own.idempotencyKey());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void recovery_failedProviderFillsBatch_otherProviderGetsTurnWithinTwoCycles(Operation operation) {
        List<PendingPayment> failed = List.of(
                prepare(PaymentProvider.OWN, operation),
                prepare(PaymentProvider.OWN, operation),
                prepare(PaymentProvider.OWN, operation)
        );
        PendingPayment toss = prepare(PaymentProvider.TOSS, operation);
        PaymentGateway tossGateway = gateway(PaymentProvider.TOSS, toss);
        PaymentGatewayRouter router = new PaymentGatewayRouter("toss", List.of(tossGateway));

        process(operation, router, 1);
        process(operation, router, 1);

        assertState(toss, "CANCELED", "CANCELED", "DONE", 5);
        for (PendingPayment own : failed) {
            assertState(own, "READY_FOR_PICKUP", "DONE", "REQUESTED", 4);
            verify(tossGateway, never()).cancel(org.mockito.ArgumentMatchers.eq(own.payment().getPaymentKey()), anyString(), anyString());
        }
        verify(tossGateway, times(1)).cancel(toss.payment().getPaymentKey(), toss.reason(), toss.idempotencyKey());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void selectBatch_oddLimitAndUnevenQueues_keepsTotalLimitAndOldestOrderWithinEachProvider(Operation operation) {
        List<PendingPayment> own = List.of(
                prepare(PaymentProvider.OWN, operation),
                prepare(PaymentProvider.OWN, operation),
                prepare(PaymentProvider.OWN, operation)
        );
        List<PendingPayment> toss = List.of(
                prepare(PaymentProvider.TOSS, operation),
                prepare(PaymentProvider.TOSS, operation)
        );
        // 같은 PG 안에서는 요청 시각을 우선하고 동률이면 ID 순서를 사용한다.
        for (PendingPayment fixture : fixtures) {
            jdbc.update("UPDATE payment_cancellations SET requested_at = ? WHERE payment_id = ?",
                    now.minusMinutes(5), fixture.payment().getId());
        }
        jdbc.update("UPDATE payment_cancellations SET requested_at = ? WHERE payment_id = ?",
                now.minusMinutes(6), own.get(2).payment().getId());

        assertThat(selectBatch(operation, 3, PaymentProvider.TOSS))
                .extracting(PaymentCancellation::getPaymentId)
                .containsExactly(toss.get(0).payment().getId(), own.get(2).payment().getId(), toss.get(1).payment().getId());
        assertThat(selectBatch(operation, 3, PaymentProvider.OWN))
                .extracting(PaymentCancellation::getPaymentId)
                .containsExactly(own.get(2).payment().getId(), toss.get(0).payment().getId(), own.get(0).payment().getId());
        // Toss가 세 번째 몫을 쓰지 못해도 OWN의 남은 요청으로 전체 용량을 채운다.
        assertThat(selectBatch(operation, 5, PaymentProvider.TOSS))
                .extracting(PaymentCancellation::getPaymentId)
                .containsExactly(toss.get(0).payment().getId(), own.get(2).payment().getId(),
                        toss.get(1).payment().getId(), own.get(0).payment().getId(), own.get(1).payment().getId());
    }

    private List<PaymentCancellation> selectBatch(Operation operation, int limit, PaymentProvider priority) {
        return operation == Operation.REFUND
                ? payments.findRequestedRefundCancellations(limit, priority)
                : payments.findRequestedCompensations(limit, priority);
    }

    private void process(Operation operation, PaymentGatewayRouter router) {
        process(operation, router, 10);
    }

    private void process(Operation operation, PaymentGatewayRouter router, int limit) {
        if (operation == Operation.REFUND) {
            new RefundFacade(refunds, router).recoverPendingCancellations(limit);
        } else {
            new PaymentCompensationProcessor(recovery, router, new PaymentApprovalResolver(router))
                    .recoverPendingCompensations(limit);
        }
    }

    private PaymentGateway gateway(PaymentProvider provider, PendingPayment fixture) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.provider()).thenReturn(provider);
        when(gateway.find(fixture.payment().getPaymentKey())).thenReturn(Optional.of(lookup(fixture)));
        when(gateway.cancel(fixture.payment().getPaymentKey(), fixture.reason(), fixture.idempotencyKey()))
                .thenReturn(result(fixture));
        return gateway;
    }

    private PaymentLookupResult lookup(PendingPayment fixture) {
        return new PaymentLookupResult(fixture.payment().getPaymentKey(), fixture.payment().getGatewayOrderId(),
                "CARD", PaymentGatewayStatus.DONE, 30_000, now, null);
    }

    private CancellationResult result(PendingPayment fixture) {
        return new CancellationResult(PaymentGatewayStatus.CANCELED, "TX-" + fixture.payment().getId(), now);
    }

    private PendingPayment prepare(PaymentProvider provider, Operation operation) {
        String key = suffix + provider + fixtures.size();
        jdbc.update("""
                INSERT INTO products (category_id, name, description, base_price, product_type,
                    preparation_days, stock_quantity, status)
                VALUES (?, ?, '', 30000, 'GENERAL', 0, 4, 'ACTIVE')
                """, categoryId, key);
        long productId = jdbc.queryForObject("SELECT id FROM products WHERE name = ?", Long.class, key);
        jdbc.update("""
                INSERT INTO orders (order_number, member_id, order_type, orderer_name, orderer_phone,
                    pickup_name, pickup_phone, original_amount, discount_amount, final_amount,
                    status, pickup_at, payment_expires_at)
                VALUES (?, ?, 'GENERAL', '주문자', '010-0000-0000', '수령자', '010-0000-0000',
                    30000, 0, 30000, 'READY_FOR_PICKUP', ?, ?)
                """, key, adminId, now.plusDays(3), now.minusMinutes(10));
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, key);
        jdbc.update("""
                INSERT INTO order_items (order_id, product_id, product_name, product_type, quantity,
                    base_price, option_amount, total_amount, preparation_days, stock_deducted_at)
                VALUES (?, ?, 'PG 복구 상품', 'GENERAL', 1, 30000, 0, 30000, 0, ?)
                """, orderId, productId, now.minusMinutes(10));
        jdbc.update("""
                INSERT INTO payments (order_id, provider, toss_order_id, payment_key, idempotency_key,
                    amount, method, status, approved_at)
                VALUES (?, ?, ?, ?, ?, 30000, 'CARD', 'DONE', ?)
                """, orderId, provider.name(), key, "KEY-" + key, "PAY-" + key, now.minusMinutes(10));
        Payment payment = payments.findDonePaymentByOrderId(orderId).orElseThrow();
        String reason;
        String idempotencyKey;
        if (operation == Operation.REFUND) {
            var request = refunds.prepareAdminCancellation(adminId, orderId, "운영 취소");
            reason = request.reason();
            idempotencyKey = request.idempotencyKey();
        } else {
            var request = recovery.createRequest(payment, payment.getPaymentKey());
            recovery.prepareCompensation(request);
            reason = request.reason();
            idempotencyKey = request.idempotencyKey();
        }
        jdbc.update("""
                UPDATE payment_cancellations SET requested_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE)
                WHERE payment_id = ?
                """, payment.getId());
        PendingPayment fixture = new PendingPayment(payment, productId, reason, idempotencyKey);
        fixtures.add(fixture);
        return fixture;
    }

    private void assertState(PendingPayment fixture, String orderStatus, String paymentStatus, String cancellationStatus, int stock) {
        long orderId = fixture.payment().getOrderId();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo(orderStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, fixture.payment().getId())).isEqualTo(paymentStatus);
        assertThat(jdbc.queryForList("SELECT status FROM payment_cancellations WHERE payment_id = ?", String.class, fixture.payment().getId()))
                .containsExactly(cancellationStatus);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, fixture.productId())).isEqualTo(stock);
        assertThat(payments.findPaymentById(fixture.payment().getId()).orElseThrow().getProvider()).isEqualTo(fixture.payment().getProvider());
    }

    private record PendingPayment(Payment payment, long productId, String reason, String idempotencyKey) {}
    enum Operation { REFUND, COMPENSATION }
    enum Failure { MISSING, TIMEOUT }
}
