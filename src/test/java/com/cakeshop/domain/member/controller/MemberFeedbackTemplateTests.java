package com.cakeshop.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MemberFeedbackTemplateTests {

    @Test
    void memberFlashMessages_useInlineMessageAreaInsteadOfPopup() throws IOException {
        List<String> templates = List.of(
                "templates/home/main.html",
                "templates/auth/login.html",
                "templates/customer/member/find-email.html",
                "templates/customer/member/find-password.html",
                "templates/customer/member/mypage.html",
                "templates/admin/member/list.html",
                "templates/admin/member/detail.html"
        );

        for (String location : templates) {
            String template = read(location);

            assertThat(template)
                    .as(location)
                    .contains("fragments/common/alert :: alert")
                    .doesNotContain("fragments/common/alert :: popup");
        }
    }

    @Test
    void signupEmailCheckFailure_usesExistingLiveMessageArea() throws IOException {
        String template = read("templates/customer/member/signup.html");

        assertThat(template)
                .contains("id=\"email-result-msg\" aria-live=\"polite\"")
                .contains("emailResultMessage.innerText = \"중복 확인 중 오류가 발생했습니다.\"")
                .contains("emailResultMessage.className = \"form-error\"")
                .doesNotContain("alert(\"중복 확인 중 오류가 발생했습니다.\")");
    }

    @Test
    void commonFeedbackFragment_doesNotExposePopupVariant() throws IOException {
        String template = read("templates/fragments/common/alert.html");

        assertThat(template)
                .contains("th:fragment=\"alert\"")
                .contains("th:src=\"@{/js/feedback.js}\"")
                .contains("role=\"status\"")
                .contains("aria-live=\"polite\"")
                .contains("role=\"alert\"")
                .contains("aria-live=\"assertive\"")
                .contains("aria-atomic=\"true\"")
                .contains("tabindex=\"-1\"")
                .contains("data-feedback-message")
                .doesNotContain("th:fragment=\"popup\"")
                .doesNotContain("window.alert(");

        String feedbackScript = read("static/js/feedback.js");
        assertThat(feedbackScript)
                .contains("[data-feedback-message][role=\"alert\"]")
                .contains("feedbackMessage.focus()");
    }

    @Test
    void feedbackScript_autoDismissesOnlySuccessMessages() throws IOException {
        String feedbackScript = read("static/js/feedback.js");

        assertThat(feedbackScript)
                .contains(".querySelectorAll('.alert--success[data-feedback-message]')")
                .contains("SUCCESS_DISMISS_DELAY_MS = 4000")
                .contains("\"mouseenter\"")
                .contains("prefers-reduced-motion: reduce")
                .doesNotContain("alert--error");

        assertThat(read("static/css/app.css")).contains(".alert.is-dismissing {");
    }

    @Test
    void commonMessageArea_usesSharedComponentSpacingAndCornerRadius() throws IOException {
        String styles = read("static/css/app.css");

        assertThat(styles)
                .contains(".alert {")
                .contains("border-radius: 6px")
                .contains("padding: var(--space-3) var(--space-4)");
    }

    @Test
    void adminLoginFeedback_exposesStatusAndAlertSemantics() throws IOException {
        String template = read("templates/auth/admin-login.html");

        assertThat(template)
                .contains("th:if=\"${param.logout}\"")
                .contains("role=\"status\"")
                .contains("aria-live=\"polite\"")
                .contains("th:if=\"${param.error}\"")
                .contains("role=\"alert\"")
                .contains("aria-live=\"assertive\"")
                .contains("th:src=\"@{/js/feedback.js}\"")
                .contains("data-feedback-message");
    }

    private String read(String location) throws IOException {
        return new ClassPathResource(location)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
