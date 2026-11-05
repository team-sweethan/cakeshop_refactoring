package com.cakeshop.domain.order.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class DonWorryPickupConfigurationTests {
    @ParameterizedTest
    @ValueSource(strings = {"local", "rds"})
    void pickupCanBeEnabledFromCommonConfiguration(String profile) throws IOException {
        var documents = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        var common = documents.getFirst();
        var environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        environment.getPropertySources().addFirst(common);
        environment.getPropertySources().addFirst(new MapPropertySource("pickup-test", Map.of(
                "DONWORRY_PICKUP_ENABLED", "true",
                "DONWORRY_PICKUP_BASE_URL", "http://localhost:8081")));

        // 프로필 전용 문서에 두면 local에서 설정 자체가 사라지므로 공통 문서를 확인한다.
        assertThat(common.getProperty("spring.config.activate.on-profile")).isNull();
        assertThat(environment.getProperty("donworry.pickup.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("donworry.pickup.base-url"))
                .isEqualTo("http://localhost:8081");
        assertThat(environment.getProperty("donworry.pickup.merchant-code")).isEqualTo("CAKESHOP");
        assertThat(environment.getProperty("donworry.pickup.key-environment-variable"))
                .isEqualTo("DONWORRY_MERCHANT_HMAC_CAKESHOP");
    }
}
