package com.cakeshop.domain.order.infra;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (order)
 * 작성일 : 2026-11-05
 * 기능 : 돈워리 화면(콘솔) 주소 설정
 * 설명 : 관리자 주문 처리 · 주문 상세의 "결제 확인 중" 안내에서 돈워리 오너 화면 "보안 조치 결과"(/owner/actions)로 가는
 *        링크를 만든다. 돈워리 API 주소(donworry.payment.base-url)와 달리 사람이 보는 화면 주소다.
 *        비어 있거나 http(s) 주소가 아니면 링크를 숨긴다. 돈워리 Case 번호는 모르므로 목록으로 간다.
 * ******************************
 */
@Getter
@Setter
@Component
@ConfigurationProperties("donworry.console")
public class DonWorryConsoleProperties {

    static final String OWNER_CASES_PATH = "/owner/actions";

    // 예: 로컬 http://localhost:5173
    private String baseUrl;

    /** 돈워리 오너 "보안 조치 결과" 목록 주소. 설정이 없거나 올바르지 않으면 null이다. */
    public String ownerCasesUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URI base = URI.create(baseUrl.trim());
            String scheme = base.getScheme();
            if (base.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            String text = base.toString();
            return (text.endsWith("/") ? text.substring(0, text.length() - 1) : text) + OWNER_CASES_PATH;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
