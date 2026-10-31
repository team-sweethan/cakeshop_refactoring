package com.cakeshop.domain.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DonWorryPaymentConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class, DonWorryPaymentClient.class);

    @Test
    void disabledByDefault_doesNotCreateClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DonWorryPaymentClient.class);
        });
    }

    @Test
    void enabledWithBaseUrl_bindsDefaults() {
        runner.withPropertyValues("donworry.payment.base-url=http://localhost:8081").run(context -> {
            DonWorryPaymentProperties properties = context.getBean(DonWorryPaymentProperties.class);
            assertThat(properties.getMerchantCode()).isEqualTo("CAKESHOP");
            assertThat(properties.getKeyEnvironmentVariable()).isEqualTo("DONWORRY_MERCHANT_HMAC_CAKESHOP");
        });
    }

    @Test
    void enabledWithoutHttpsOrLocalUrl_failsAtStartup() {
        runner.withPropertyValues("donworry.payment.enabled=true",
                        "donworry.payment.base-url=http://donworry.example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DonWorryPaymentProperties.class)
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
