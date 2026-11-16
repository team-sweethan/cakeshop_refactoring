package com.cakeshop.domain.notification.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cakeshop.global.config.MariaDbIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@MybatisTest
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class OrderSmsRetryMapperTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final String REJECTED = "Solapi message delivery failed";

    @Autowired
    private NotificationMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void selectsOnlyRecentOrderSmsWithOneExplicitFailure() {
        String token = UUID.randomUUID().toString().substring(0, 12);
        String email = token + "@example.test";
        jdbc.update("""
                INSERT INTO members (email, password, name, nickname, phone, role, status)
                VALUES (?, NULL, '테스트 회원', ?, '010-0000-0000', 'USER', 'ACTIVE')
                """, email, token);
        long member = jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
        jdbc.update("""
                INSERT INTO orders (order_number, member_id, orderer_name, orderer_phone, pickup_name, pickup_phone,
                    original_amount, discount_amount, final_amount, status, pickup_at, created_at, updated_at, order_type)
                VALUES (?, ?, '테스트', '010-0000-0000', '테스트', '010-0000-0000',
                    10000, 0, 10000, 'READY_FOR_PICKUP', ?, ?, ?, 'GENERAL')
                """, token, member, NOW.plusHours(2), NOW, NOW);
        long order = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, token);

        long eligible = insertAlert(member, order, token + "-eligible", "WEB_AND_SMS", NOW);
        long unknown = insertAlert(member, order, token + "-unknown", "WEB_AND_SMS", NOW);
        long exhausted = insertAlert(member, order, token + "-exhausted", "WEB_AND_SMS", NOW);
        long webOnly = insertAlert(member, order, token + "-web", "WEB_ONLY", NOW);
        long expired = insertAlert(member, order, token + "-expired", "WEB_AND_SMS", NOW.minusDays(2));
        addFailure(eligible, REJECTED);
        addFailure(unknown, "Solapi API communication failure");
        addFailure(exhausted, REJECTED);
        addFailure(exhausted, REJECTED);
        addFailure(webOnly, REJECTED);
        addFailure(expired, REJECTED);

        var candidates = mapper.findRetryableOrderNotifications(50, NOW.minusDays(1));
        assertThat(candidates).filteredOn(candidate -> Long.valueOf(order).equals(candidate.getOrderId()))
                .singleElement().satisfies(candidate -> assertThat(candidate.getId()).isEqualTo(eligible));
    }

    private long insertAlert(long member, long order, String key, String scope, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO notifications (receiver_id, order_id, notification_type, title, content, delivery_scope,
                    is_read, event_key, created_at, last_event_at)
                VALUES (?, ?, 'CUSTOMER_PICKUP_REMINDER_TODAY', '픽업', '예약 확인', ?, FALSE, ?, ?, ?)
                """, member, order, scope, key, createdAt, createdAt);
        return jdbc.queryForObject("SELECT id FROM notifications WHERE event_key = ?", Long.class, key);
    }

    private void addFailure(long id, String reason) {
        jdbc.update("""
                INSERT INTO notification_deliveries (notification_id, recipient, template_code, status, failure_reason)
                VALUES (?, '010-0000-0000', 'DEFAULT_SMS', 'FAILED', ?)
                """, id, reason);
    }
}
