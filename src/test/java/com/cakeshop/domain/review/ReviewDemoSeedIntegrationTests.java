package com.cakeshop.domain.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.cakeshop.global.config.MariaDbIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

/** 시연용 회원·후기 seed 가 넣는 데이터와 재실행 계약을 검증한다. */
@SpringBootTest
@MariaDbIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(
        scripts = "classpath:db/seed/seed-local.sql",
        config = @SqlConfig(encoding = "UTF-8"),
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
class ReviewDemoSeedIntegrationTests {

    private static final String DEMO_REVIEW_SEED = "db/seed/seed-review-demo.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 회원 12명이 픽업 완료·결제 완료 주문 40건에 공개 후기를 1건씩 남기고, 상품 평균·후기 수가 그 후기와 맞는다
    @Test
    void demoReviewSeed_insertsMembersOrdersAndReviewsOnceAndKeepsAggregatesInSync() {
        runDemoReviewSeed();

        assertThat(count(
                """
                SELECT COUNT(*)
                FROM members m
                INNER JOIN members admin ON admin.email = 'admin@cakeshop.local'
                WHERE m.email LIKE 'member__@cakeshop.local'
                  AND m.role = 'USER'
                  AND m.status = 'ACTIVE'
                  AND m.password = admin.password
                """
        )).isEqualTo(12);
        assertThat(count(
                """
                SELECT COUNT(*)
                FROM orders o
                INNER JOIN order_items oi ON oi.order_id = o.id
                INNER JOIN payments payment ON payment.order_id = o.id
                INNER JOIN reviews review ON review.order_item_id = oi.id
                WHERE o.order_number LIKE 'DEMO-REVIEW-%'
                  AND o.status = 'PICKED_UP'
                  AND payment.status = 'DONE'
                  AND payment.amount = o.final_amount
                  AND review.status = 'PUBLISHED'
                  AND review.member_id = o.member_id
                  AND review.created_at > o.picked_up_at
                """
        )).isEqualTo(40);
        // 주문 제작 주문 5건은 옵션 스냅샷(크기·크림 색상)을 2개씩 갖는다
        assertThat(count(
                """
                SELECT COUNT(*)
                FROM order_item_options option_snapshot
                INNER JOIN order_items oi ON oi.id = option_snapshot.order_item_id
                INNER JOIN orders o ON o.id = oi.order_id
                WHERE o.order_number LIKE 'DEMO-REVIEW-L%'
                """
        )).isEqualTo(10);

        assertThat(aggregates()).containsExactly(
                "딸기 생크림 케이크 1호 8 4.75",
                "초코 가나슈 케이크 1호 다크초코 6 4.50",
                "레몬 바스크 치즈케이크 5 4.60",
                "레터링 생크림 케이크 5 4.40"
        );

        jdbcTemplate.update(
                """
                UPDATE reviews
                SET status = 'BLOCKED'
                WHERE order_item_id = (
                    SELECT oi.id
                    FROM order_items oi
                    INNER JOIN orders o ON o.id = oi.order_id
                    WHERE o.order_number = 'DEMO-REVIEW-S01'
                )
                """);

        runDemoReviewSeed();

        assertThat(count("SELECT COUNT(*) FROM members WHERE email LIKE 'member__@cakeshop.local'"))
                .isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE order_number LIKE 'DEMO-REVIEW-%'"))
                .isEqualTo(40);
        assertThat(count("SELECT COUNT(*) FROM reviews")).isEqualTo(40);
        // 관리자가 가린 후기는 되살리지 않고, 평균·후기 수에서도 빠진다
        assertThat(count(
                """
                SELECT COUNT(*)
                FROM products
                WHERE name = '미니 도시락 케이크'
                  AND review_count = 0
                  AND average_rating = 0.00
                """
        )).isOne();
    }

    // 관리자 계정이 없으면 아무것도 넣지 않는다
    @Test
    void demoReviewSeed_withoutAdmin_insertsNothing() {
        renameAdmin("admin@cakeshop.local", "renamed-admin@cakeshop.local");
        try {
            // 다른 테스트가 먼저 돌아 주문이 있어도, 관리자가 없으면 빠진 후기를 채우지 않아야 한다
            jdbcTemplate.update(
                    """
                    DELETE review
                    FROM reviews review
                    INNER JOIN order_items oi ON oi.id = review.order_item_id
                    INNER JOIN orders o ON o.id = oi.order_id
                    WHERE o.order_number LIKE 'DEMO-REVIEW-%'
                    """);
            long ordersBefore = count("SELECT COUNT(*) FROM orders WHERE order_number LIKE 'DEMO-REVIEW-%'");

            runDemoReviewSeed();

            assertThat(count("SELECT COUNT(*) FROM orders WHERE order_number LIKE 'DEMO-REVIEW-%'"))
                    .isEqualTo(ordersBefore);
            assertThat(count(
                    """
                    SELECT COUNT(*)
                    FROM reviews review
                    INNER JOIN order_items oi ON oi.id = review.order_item_id
                    INNER JOIN orders o ON o.id = oi.order_id
                    WHERE o.order_number LIKE 'DEMO-REVIEW-%'
                    """
            )).isZero();
        } finally {
            renameAdmin("renamed-admin@cakeshop.local", "admin@cakeshop.local");
        }
    }

    private void renameAdmin(String from, String to) {
        jdbcTemplate.update("UPDATE members SET email = ? WHERE email = ?", to, from);
    }

    private List<String> aggregates() {
        return jdbcTemplate.queryForList(
                """
                SELECT CONCAT(name, ' ', review_count, ' ', average_rating)
                FROM products
                WHERE review_count > 0
                ORDER BY review_count DESC, average_rating DESC, id DESC
                LIMIT 4
                """,
                String.class
        );
    }

    private void runDemoReviewSeed() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(DEMO_REVIEW_SEED));
            return null;
        });
    }

    private long count(String sql) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
