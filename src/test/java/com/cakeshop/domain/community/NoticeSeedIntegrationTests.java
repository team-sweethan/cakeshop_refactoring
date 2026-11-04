package com.cakeshop.domain.community;

import static org.assertj.core.api.Assertions.assertThat;

import com.cakeshop.global.config.MariaDbIntegrationTest;

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

@SpringBootTest
@MariaDbIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(
        scripts = "classpath:db/seed/seed-local.sql",
        config = @SqlConfig(encoding = "UTF-8"),
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
class NoticeSeedIntegrationTests {

    private static final String NOTICE_SEED = "db/seed/seed-notice.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 관리자 작성 공지 5건이 노출 상태로 들어가고, 다시 실행해도 늘지 않으며 관리자가 고친 내용을 덮어쓰지 않는다
    @Test
    void noticeSeed_insertsPublishedNoticesOnceAndKeepsEditedOnes() {
        jdbcTemplate.update("DELETE FROM community_notices");

        runNoticeSeed();

        assertThat(count("SELECT COUNT(*) FROM community_notices")).isEqualTo(5);
        assertThat(count(
                """
                SELECT COUNT(*)
                FROM community_notices n
                INNER JOIN members m ON m.id = n.created_by
                WHERE n.status = 'PUBLISHED'
                  AND m.email = 'admin@cakeshop.local'
                  AND m.role = 'ADMIN'
                """
        )).isEqualTo(5);

        jdbcTemplate.update(
                """
                UPDATE community_notices
                SET content = '관리자가 고친 내용', status = 'DELETED'
                WHERE title = '주문 취소와 환불 안내'
                """);

        runNoticeSeed();

        assertThat(count("SELECT COUNT(*) FROM community_notices")).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM community_notices WHERE title = '주문 취소와 환불 안내'",
                String.class
        )).isEqualTo("관리자가 고친 내용");
    }

    private void runNoticeSeed() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(NOTICE_SEED));
            return null;
        });
    }

    private long count(String sql) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
