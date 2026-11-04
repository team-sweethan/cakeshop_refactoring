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
class StoreSeedIntegrationTests {

    private static final String LOCAL_SEED = "db/seed/seed-local.sql";
    private static final String STORE_IMAGE_URL = "/images/home/store.jpg";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 사진이 비어 있으면 저장소의 정적 매장 사진으로 채우고, 관리자가 올린 사진은 다시 실행해도 그대로 둔다
    @Test
    void localSeed_fillsEmptyStoreImageAndKeepsUploadedImage() {
        jdbcTemplate.update("UPDATE store SET image_url = NULL WHERE id = 1");

        runLocalSeed();

        assertThat(storeImageUrl()).isEqualTo(STORE_IMAGE_URL);
        assertThat(new ClassPathResource("static" + STORE_IMAGE_URL).exists()).isTrue();

        jdbcTemplate.update(
                "UPDATE store SET image_url = '/uploads/store/admin-uploaded.jpg' WHERE id = 1");

        runLocalSeed();

        assertThat(storeImageUrl()).isEqualTo("/uploads/store/admin-uploaded.jpg");
    }

    private String storeImageUrl() {
        return jdbcTemplate.queryForObject("SELECT image_url FROM store WHERE id = 1", String.class);
    }

    private void runLocalSeed() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(LOCAL_SEED));
            return null;
        });
    }
}
