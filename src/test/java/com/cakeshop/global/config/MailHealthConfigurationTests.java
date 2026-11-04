package com.cakeshop.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailHealthContributorAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MailHealthConfigurationTests {

    // 실제 application.yml 을 읽어 메일 발송 Bean 은 그대로 두고 메일 상태 확인만 빠지는지 본다.
    // SMTP 에는 연결하지 않는다.
    private ApplicationContextRunner contextRunner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.location=classpath:application.yml",
                        "spring.config.import=optional:classpath:missing-test-env.properties",
                        "spring.profiles.active=" + profile,
                        "MAIL_USERNAME=sender@example.com")
                .withConfiguration(AutoConfigurations.of(
                        MailSenderAutoConfiguration.class,
                        MailHealthContributorAutoConfiguration.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "server"})
    void mailHealthIndicator_isDisabledWhileMailSenderRemains(String profile) {
        contextRunner(profile).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("mailSender");
            assertThat(context).doesNotHaveBean(MailHealthIndicator.class);
        });
    }

    // 위 검사가 설정 덕분에 통과하는지 확인한다. 설정을 켜면 상태 확인 Bean 이 다시 생긴다.
    @ParameterizedTest
    @ValueSource(strings = {"local", "server"})
    void mailHealthIndicator_isCreatedWhenExplicitlyEnabled(String profile) {
        contextRunner(profile)
                .withPropertyValues("management.health.mail.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MailHealthIndicator.class));
    }
}
