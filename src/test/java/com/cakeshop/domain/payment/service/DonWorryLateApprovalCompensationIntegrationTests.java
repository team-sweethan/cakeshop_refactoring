package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.order.service.payment.OrderPaymentRecoveryService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-04
 * 기능 : 결제 기한 뒤 늦게 승인된 돈워리 결제의 보상 취소를 실제 DB로 검증
 * 설명 : 만료 배치가 주문 · 결제를 EXPIRED로 바꾼 뒤 승인이 확인돼도 기존 보상 취소(SYSTEM_COMPENSATION)로
 *        결제는 CANCELED(INTERNAL_COMPLETION_FAILED), 주문은 CANCELED가 된다 (pg-decisions 008).
 *        보상 요청이 있는 동안 만료 배치는 같은 주문을 건드리지 않는다.
 * ******************************
 */
@MybatisTest
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentRecoveryService.class, OrderPaymentRecoveryService.class, ProductStockService.class})
@Transactional
class DonWorryLateApprovalCompensationIntegrationTests {

    @Autowired
    private PaymentRecoveryService recoveryService;
    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private CouponOrderCommandService coupons;

    private String suffix;
    private long orderId;
    private long paymentId;

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
        jdbc.update("""
                INSERT INTO members (email, password, name, nickname, phone, role, status)
                VALUES (?, NULL, '늦은 승인 회원', '늦은승인', '010-0000-0000', 'USER', 'ACTIVE')
                """, "late-approval-" + suffix + "@example.com");
        long memberId = jdbc.queryForObject(
                "SELECT id FROM members WHERE email = ?", Long.class, "late-approval-" + suffix + "@example.com");
        jdbc.update("""
                INSERT INTO orders (
                    order_number, member_id, order_type, orderer_name, orderer_phone, pickup_name, pickup_phone,
                    original_amount, discount_amount, final_amount, status, pickup_at, payment_expires_at, expired_at
                )
                VALUES (?, ?, 'GENERAL', '주문자', '010-1111-2222', '수령자', '010-3333-4444',
                        25000, 0, 25000, 'EXPIRED',
                        CURRENT_TIMESTAMP(6) + INTERVAL 1 DAY,
                        CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE,
                        CURRENT_TIMESTAMP(6))
                """, "LATE-APPROVAL-" + suffix, memberId);
        orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", Long.class, "LATE-APPROVAL-" + suffix);
        jdbc.update("""
                INSERT INTO payments (
                    order_id, provider, toss_order_id, idempotency_key, amount, status, failure_code
                )
                VALUES (?, 'OWN', ?, ?, 25000, 'EXPIRED', 'ORDER_EXPIRED')
                """, orderId, "LATE-ORD-" + suffix, "LATE-IDEMPOTENCY-" + suffix);
        paymentId = jdbc.queryForObject(
                "SELECT id FROM payments WHERE toss_order_id = ?", Long.class, "LATE-ORD-" + suffix);
    }

    @Test
    void expiredPaymentApprovedLate_compensationCancelsPaymentAndOrder() {
        Payment expired = paymentMapper.findPaymentById(paymentId).orElseThrow();
        CompensationRequest request = recoveryService.createRequest(expired, "DW-101");

        recoveryService.prepareCompensation(request);
        Map<String, Object> pending = jdbc.queryForMap(
                "SELECT status, request_type FROM payment_cancellations WHERE payment_id = ?", paymentId);
        assertThat(pending).containsEntry("status", "REQUESTED").containsEntry("request_type", "SYSTEM_COMPENSATION");
        assertThat(jdbc.queryForObject("SELECT payment_key FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("DW-101");

        LocalDateTime canceledAt = LocalDateTime.of(2026, 11, 4, 12, 30);
        recoveryService.completeCompensation(
                request, new CancellationResult(PaymentGatewayStatus.CANCELED, "DW-CANCEL-1", canceledAt));

        assertThat(jdbc.queryForMap("SELECT status, failure_code FROM payments WHERE id = ?", paymentId))
                .containsEntry("status", "CANCELED")
                .containsEntry("failure_code", "INTERNAL_COMPLETION_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payment_cancellations WHERE payment_id = ?", String.class, paymentId))
                .isEqualTo("DONE");

        // 웹훅 · 조회 배치가 같은 늦은 승인을 다시 처리해도 한 번만 반영된다
        recoveryService.completeCompensation(
                request, new CancellationResult(PaymentGatewayStatus.CANCELED, "DW-CANCEL-1", canceledAt));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_cancellations WHERE payment_id = ?", Integer.class, paymentId))
                .isEqualTo(1);
    }
}
