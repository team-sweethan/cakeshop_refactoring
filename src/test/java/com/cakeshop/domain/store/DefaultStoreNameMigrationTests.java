package com.cakeshop.domain.store;

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

@SpringBootTest
@MariaDbIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DefaultStoreNameMigrationTests {

    private static final String MIGRATION =
            "db/migration/V20261104_162803__rename_default_store_to_sweethan_cake.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Flyway 가 모든 migration 을 적용하면 기본값으로 만든 대표 매장 이름은 서비스 이름과 같다
    @Test
    void migratedDatabase_defaultStoreUsesServiceName() {
        assertThat(storeName()).isEqualTo("스윗한 케이크");
    }

    // 기본값 그대로일 때만 바꾸고, 운영자가 바꾼 이름은 덮어쓰지 않는다
    @Test
    void migration_onlyRenamesUntouchedDefaultName() {
        String original = storeName();
        try {
            jdbcTemplate.update("UPDATE store SET name = '운영자가 바꾼 이름' WHERE id = 1");
            runMigrationScript();
            assertThat(storeName()).isEqualTo("운영자가 바꾼 이름");

            jdbcTemplate.update("UPDATE store SET name = '케이크 공방' WHERE id = 1");
            runMigrationScript();
            assertThat(storeName()).isEqualTo("스윗한 케이크");
        } finally {
            jdbcTemplate.update("UPDATE store SET name = ? WHERE id = 1", original);
        }
    }

    private String storeName() {
        return jdbcTemplate.queryForObject("SELECT name FROM store WHERE id = 1", String.class);
    }

    private void runMigrationScript() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            return null;
        });
    }
}
