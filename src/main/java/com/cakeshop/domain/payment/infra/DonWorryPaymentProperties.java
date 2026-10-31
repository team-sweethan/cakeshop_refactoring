package com.cakeshop.domain.payment.infra;

import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제 API 송신 설정
 * 설명 : 기본 비활성이며 HMAC 키 원문 대신 키를 담은 환경변수 이름을 설정한다.
 * ******************************
 */
@Getter
@Setter
@Component
@ConfigurationProperties("donworry.payment")
public class DonWorryPaymentProperties {
    private boolean enabled;
    private URI baseUrl;
    private String merchantCode = "CAKESHOP";
    // 키 원문이 아니라 환경변수 이름만 설정한다. DonWorry와 같은 이름을 쓴다.
    private String keyEnvironmentVariable = "DONWORRY_MERCHANT_HMAC_CAKESHOP";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
}
