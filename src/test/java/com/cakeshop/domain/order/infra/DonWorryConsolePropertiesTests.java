package com.cakeshop.domain.order.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.cakeshop.domain.order.controller.admin.AdminOrderDonWorryLinkAdvice;
import org.junit.jupiter.api.Test;

// 관리자 "결제 확인 중" 안내의 돈워리 링크: 화면 주소가 있으면 오너 "보안 조치 결과"로, 없거나 올바르지 않으면 숨긴다.
class DonWorryConsolePropertiesTests {

    @Test
    void ownerCasesUrl_validBaseUrl_pointsToOwnerActions() {
        assertThat(properties("http://localhost:5173").ownerCasesUrl())
                .isEqualTo("http://localhost:5173/owner/actions");
        assertThat(properties("https://console.donworry.example/").ownerCasesUrl())
                .isEqualTo("https://console.donworry.example/owner/actions");
    }

    @Test
    void ownerCasesUrl_blankOrInvalid_hidesLink() {
        assertThat(properties(null).ownerCasesUrl()).isNull();
        assertThat(properties("  ").ownerCasesUrl()).isNull();
        assertThat(properties("javascript:alert(1)").ownerCasesUrl()).isNull();
        assertThat(properties("/owner").ownerCasesUrl()).isNull();
        assertThat(properties("http://exa mple.com").ownerCasesUrl()).isNull();
    }

    @Test
    void advice_exposesOwnerCasesUrlAndHidesWithoutSettings() {
        assertThat(advice(properties("http://localhost:5173")).donWorryOwnerCasesUrl())
                .isEqualTo("http://localhost:5173/owner/actions");
        assertThat(advice(properties("")).donWorryOwnerCasesUrl()).isNull();
        assertThat(advice(null).donWorryOwnerCasesUrl()).isNull();
    }

    private AdminOrderDonWorryLinkAdvice advice(DonWorryConsoleProperties properties) {
        org.springframework.beans.factory.support.StaticListableBeanFactory beans =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        if (properties != null) {
            beans.addBean("donWorryConsoleProperties", properties);
        }
        return new AdminOrderDonWorryLinkAdvice(beans.getBeanProvider(DonWorryConsoleProperties.class));
    }

    private DonWorryConsoleProperties properties(String baseUrl) {
        DonWorryConsoleProperties properties = new DonWorryConsoleProperties();
        properties.setBaseUrl(baseUrl);
        return properties;
    }
}
