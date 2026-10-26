package com.cakeshop.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.cakeshop.global.infra.FileStorageClient;
import com.cakeshop.global.infra.LocalFileStorageClient;
import com.cakeshop.global.storage.S3StorageService;

class ServerProfileConfigurationTests {

    @TempDir
    Path uploadDir;

    @Test
    void server_withoutAwsOrOauthCredentials_usesDiskAndKeepsPublicPreviewDisabled() {
        // 실제 config-data 로딩과 저장소 Bean 선택만 검증한다. DB/외부 API는 연결하지 않는다.
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.location=classpath:application.yml",
                        "spring.config.import=optional:classpath:missing-test-env.properties",
                        "spring.profiles.active=server",
                        "DB_DATABASE=cakeshop_test",
                        "DB_USERNAME=test",
                        "DB_PASSWORD=test",
                        "FILE_UPLOAD_DIR=" + uploadDir)
                .withUserConfiguration(
                        ClockConfig.class, AwsS3Config.class,
                        LocalFileStorageClient.class, S3StorageService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FileStorageClient.class);
                    assertThat(context.getBean(FileStorageClient.class))
                            .isInstanceOf(LocalFileStorageClient.class);
                    assertThat(context).doesNotHaveBean(S3StorageService.class);
                    var environment = context.getEnvironment();
                    assertThat(environment.getActiveProfiles()).containsExactly("server");
                    assertThat(environment.getProperty("spring.datasource.url"))
                            .startsWith("jdbc:mariadb://localhost:3306/cakeshop_test?")
                            .contains("connectionTimeZone=+09:00");
                    assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("app.mockup.public-preview", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("app.monitoring.metrics-public", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                            .isEqualTo("health");
                });
    }
}
