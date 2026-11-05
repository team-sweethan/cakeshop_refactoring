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

/** 로컬 seed 의 커뮤니티 카테고리 복구와 시연용 게시글 seed 를 검증한다. */
@SpringBootTest
@MariaDbIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(
        scripts = {
                "classpath:db/seed/seed-local.sql",
                "classpath:db/seed/seed-review-demo.sql"
        },
        config = @SqlConfig(encoding = "UTF-8"),
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
class CommunityDemoSeedIntegrationTests {

    private static final String COMMUNITY_DEMO_SEED = "db/seed/seed-community-demo.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // seed-local 이 카테고리를 지운 뒤 기본 3개를 다시 넣어, 목록 필터와 글쓰기에 카테고리가 남는다
    @Test
    void localSeed_restoresDefaultPostCategories() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT code FROM post_categories WHERE is_active = 1 ORDER BY sort_order",
                String.class
        )).containsExactly("QNA", "REVIEW", "FREE");
    }

    // 시연 회원 글 24건(카테고리별 8건)과 댓글·답글·좋아요가 한 번만 들어가고 좋아요 수가 행 수와 맞는다
    @Test
    void communityDemoSeed_insertsPostsCommentsAndLikesOnce() {
        runCommunityDemoSeed();
        runCommunityDemoSeed();

        assertThat(count(
                """
                SELECT COUNT(*)
                FROM posts p
                INNER JOIN members m ON m.id = p.member_id
                WHERE m.email LIKE 'member__@cakeshop.local'
                  AND p.status = 'PUBLISHED'
                """
        )).isEqualTo(24);
        assertThat(jdbcTemplate.queryForList(
                """
                SELECT CONCAT(c.code, ' ', COUNT(*))
                FROM posts p
                INNER JOIN post_categories c ON c.id = p.category_id
                GROUP BY c.code
                ORDER BY c.code
                """,
                String.class
        )).containsExactly("FREE 8", "QNA 8", "REVIEW 8");

        assertThat(count("SELECT COUNT(*) FROM comments")).isEqualTo(48);
        // 답글은 같은 글의 뿌리 댓글에만 달린다
        assertThat(count(
                """
                SELECT COUNT(*)
                FROM comments reply
                INNER JOIN comments parent ON parent.id = reply.parent_comment_id
                WHERE reply.post_id = parent.post_id
                  AND parent.parent_comment_id IS NULL
                """
        )).isEqualTo(12);
        // 질문 글 8건 모두에 관리자 답변이 있다
        assertThat(count(
                """
                SELECT COUNT(DISTINCT c.post_id)
                FROM comments c
                INNER JOIN members m ON m.id = c.member_id
                INNER JOIN posts p ON p.id = c.post_id
                INNER JOIN post_categories category ON category.id = p.category_id
                WHERE m.email = 'admin@cakeshop.local'
                  AND category.code = 'QNA'
                """
        )).isEqualTo(8);

        assertThat(count("SELECT COUNT(*) FROM post_likes")).isPositive();
        assertThat(count(
                """
                SELECT COUNT(*)
                FROM posts p
                WHERE p.like_count <> (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id)
                """
        )).isZero();
        // 좋아요는 작성자 본인이 누르지 않고, 글보다 늦게 가입한 회원도 누르지 않는다
        assertThat(count(
                """
                SELECT COUNT(*)
                FROM post_likes l
                INNER JOIN posts p ON p.id = l.post_id
                INNER JOIN members m ON m.id = l.member_id
                WHERE l.member_id = p.member_id
                   OR m.created_at > p.created_at
                   OR l.created_at < p.created_at
                """
        )).isZero();
    }

    private void runCommunityDemoSeed() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(COMMUNITY_DEMO_SEED));
            return null;
        });
    }

    private long count(String sql) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
