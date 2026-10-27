package com.cakeshop.domain.payment.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import javax.sql.DataSource;
import java.math.BigDecimal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 기존 데이터가 존재하는 직전 스키마에서 PG 식별자를 추가하는 업그레이드 경로다. */
@MybatisTest(properties = "spring.flyway.enabled=false")
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentProviderMigrationTests {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentMapper paymentMapper;

    @Test
    void upgrade_existingPaymentsAndPendingRefunds_preservesTossAndSupportsExplicitOwn() {
        migrateTo("20260819.173713");
        jdbc.update("""
                INSERT INTO members (email, name, nickname, phone, role, status)
                VALUES ('provider-upgrade@example.com', '전환', '전환회원', '010-0000-0000', 'USER', 'ACTIVE')
                """);
        long memberId = jdbc.queryForObject("SELECT id FROM members WHERE email = 'provider-upgrade@example.com'", Long.class);
        for (String status : new String[]{"READY", "DONE", "CANCELED", "EXPIRED"}) {
            jdbc.update("""
                    INSERT INTO orders (order_number, member_id, order_type, orderer_name, orderer_phone,
                        pickup_name, pickup_phone, original_amount, discount_amount, final_amount,
                        status, pickup_at, payment_expires_at)
                    VALUES (?, ?, 'GENERAL', '주문자', '010-0000-0000', '수령자', '010-0000-0000',
                        30000, 0, 30000, 'PENDING_PAYMENT', '2026-11-01 12:00:00', '2026-10-26 12:00:00')
                    """, "UPGRADE-" + status, memberId);
            long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, "UPGRADE-" + status);
            jdbc.update("""
                    INSERT INTO payments (order_id, toss_order_id, payment_key, idempotency_key, amount, status)
                    VALUES (?, ?, ?, ?, 30000, ?)
                    """, orderId, "UPGRADE-" + status, "KEY-" + status, "PAY-" + status, status);
        }
        long donePaymentId = jdbc.queryForObject("SELECT id FROM payments WHERE status = 'DONE'", Long.class);
        jdbc.update("""
                INSERT INTO payment_cancellations (payment_id, idempotency_key, cancel_amount,
                    cancel_reason, request_type, requested_by, status)
                VALUES (?, 'CANCEL-UPGRADE', 30000, '전환 중 환불', 'CUSTOMER', ?, 'REQUESTED')
                """, donePaymentId, memberId);

        migrateTo("20261026.195117");

        assertThat(jdbc.queryForList("SELECT provider FROM payments ORDER BY id", String.class))
                .containsExactly("TOSS", "TOSS", "TOSS", "TOSS");
        assertThat(jdbc.queryForList("SELECT status FROM payments ORDER BY id", String.class))
                .containsExactly("READY", "DONE", "CANCELED", "EXPIRED");
        assertThat(paymentMapper.findPaymentById(donePaymentId).orElseThrow().getProvider()).isEqualTo(PaymentProvider.TOSS);
        assertThat(jdbc.queryForList("SELECT status FROM payment_cancellations WHERE payment_id = ?", String.class, donePaymentId))
                .containsExactly("REQUESTED");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET provider = 'UNKNOWN' WHERE id = ?", donePaymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET provider = NULL WHERE id = ?", donePaymentId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 완료된 결제 시도가 없는 기존 주문에 새 OWN 시도를 명시해 저장하고 모든 조회 매핑을 확인한다.
        long expiredOrderId = jdbc.queryForObject("SELECT order_id FROM payments WHERE status = 'EXPIRED'", Long.class);
        Payment own = new Payment();
        own.setOrderId(expiredOrderId);
        own.setProvider(PaymentProvider.OWN);
        own.setGatewayOrderId("OWN-UPGRADE");
        own.setIdempotencyKey("PAY-OWN-UPGRADE");
        own.setAmount(BigDecimal.valueOf(30_000));
        assertThat(paymentMapper.insertReadyPayment(own)).isEqualTo(1);
        assertThat(paymentMapper.findReadyPaymentByOrderId(expiredOrderId).orElseThrow().getProvider()).isEqualTo(PaymentProvider.OWN);
        assertThat(paymentMapper.findLatestPaymentByOrderId(expiredOrderId).orElseThrow().getProvider()).isEqualTo(PaymentProvider.OWN);
        assertThat(paymentMapper.completeIfReady(own.getId(), "OWN-KEY", "CARD", java.time.LocalDateTime.of(2026, 10, 26, 12, 0))).isEqualTo(1);
        assertThat(paymentMapper.findDonePaymentByOrderId(expiredOrderId).orElseThrow().getProvider()).isEqualTo(PaymentProvider.OWN);
        assertThat(paymentMapper.findPaymentById(donePaymentId).orElseThrow().getProvider()).isEqualTo(PaymentProvider.TOSS);
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(version).load().migrate();
    }
}
