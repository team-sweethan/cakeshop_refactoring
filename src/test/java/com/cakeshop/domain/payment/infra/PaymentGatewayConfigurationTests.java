package com.cakeshop.domain.payment.infra;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.service.PaymentApprovalResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentGatewayConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(
                    TossPaymentGateway.class, TossPaymentClient.class, TossPaymentAvailability.class, PaymentGatewayRouter.class)
            .withPropertyValues(
                    "app.payment.toss.base-url=https://payments.test",
                    "app.payment.toss.client-key=test-client-key",
                    "app.payment.toss.secret-key=test-secret-key");

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "toss")
    void gateway_missingOrToss_selectsExistingTossGateway(String gateway) {
        ApplicationContextRunner runner = gateway == null
                ? contextRunner
                : contextRunner.withPropertyValues("app.payment.gateway=" + gateway);

        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PaymentGateway.class);
            PaymentGateway selected = context.getBean(PaymentGateway.class);
            assertThat(selected).isInstanceOf(TossPaymentGateway.class);
            assertThat(selected.isAvailable()).isTrue();
            assertThat(selected.clientKey()).isEqualTo("test-client-key");
        });
    }

    @Test
    void applicationConfig_defaultSelection_usesToss() {
        configDataRunner()
                .withPropertyValues("spring.profiles.active=gateway-config-test")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PaymentGateway.class);
                    assertThat(context.getEnvironment().getProperty("app.payment.gateway")).isEqualTo("toss");
                    assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(TossPaymentGateway.class);
                });
    }

    @Test
    void testProfile_ownEnvironmentSelection_keepsExistingTestsOnToss() {
        configDataRunner()
                .withPropertyValues("spring.profiles.active=test", "PAYMENT_GATEWAY=own")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PaymentGateway.class);
                    assertThat(context.getEnvironment().getProperty("app.payment.gateway")).isEqualTo("toss");
                    assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(TossPaymentGateway.class);
                });
    }

    @Test
    void gateway_ownSelection_keepsTossForExistingPayments() {
        PaymentGateway own = org.mockito.Mockito.mock(PaymentGateway.class);
        org.mockito.Mockito.when(own.provider()).thenReturn(PaymentProvider.OWN);
        configDataRunner()
                .withBean("ownPaymentGateway", PaymentGateway.class, () -> own)
                .withPropertyValues("spring.profiles.active=gateway-config-test", "PAYMENT_GATEWAY=own")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TossPaymentGateway.class);
                    PaymentGatewayRouter router = context.getBean(PaymentGatewayRouter.class);
                    assertThat(router.forProvider(PaymentProvider.TOSS))
                            .isSameAs(context.getBean(TossPaymentGateway.class));
                    assertThat(router.forProvider(PaymentProvider.OWN)).isSameAs(own);
                });
    }

    @Test
    void gateway_ownWithoutImplementation_stopsApplicationDespiteExistingToss() {
        configDataRunner()
                .withUserConfiguration(PaymentApprovalResolver.class)
                .withPropertyValues("spring.profiles.active=gateway-config-test", "PAYMENT_GATEWAY=own")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("Selected payment gateway implementation is unavailable");
                });
    }

    @Test
    void applicationConfig_ownEnvironmentValues_exposesReservedSettingsOnly() {
        configDataRunner()
                .withPropertyValues(
                        "spring.profiles.active=gateway-config-test",
                        "PAYMENT_GATEWAY=toss",
                        "OWN_PG_BASE_URL=https://own-payments.test",
                        "OWN_PG_API_KEY=test-own-api-key",
                        "OWN_PG_CONNECT_TIMEOUT=2s",
                        "OWN_PG_READ_TIMEOUT=4s")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PaymentGateway.class);
                    assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(TossPaymentGateway.class);
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("app.payment.own.base-url"))
                            .isEqualTo("https://own-payments.test");
                    assertThat(environment.getProperty("app.payment.own.api-key")).isEqualTo("test-own-api-key");
                    assertThat(environment.getProperty("app.payment.own.connect-timeout")).isEqualTo("2s");
                    assertThat(environment.getProperty("app.payment.own.read-timeout")).isEqualTo("4s");
                });
    }

    private ApplicationContextRunner configDataRunner() {
        // 실제 YAML을 읽되 로컬 .env와 DB·외부 API에는 접근하지 않는다.
        return contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.location=classpath:application.yml",
                        "spring.config.import=optional:classpath:missing-gateway-test-env.properties");
    }
}
