package com.cakeshop.domain.order.infra;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
/**
 * ******************************
 * 작성자 : 민정
 * 담당자 : 주환 (order), 민정 (P6 연동)
 * 작성일 : 2026-10-29
 * 기능 : P6 픽업 송신 설정
 * 설명 : 기본 비활성이며 HMAC 키 원문 대신 환경변수 이름을 설정한다.
 * ******************************
 */
@Component
@ConfigurationProperties("donworry.pickup")
public class DonWorryPickupProperties {
    private boolean enabled;
    private URI baseUrl;
    private String merchantCode = "CAKESHOP";
    // 키 원문이 아니라 환경변수 이름만 설정한다.
    private String keyEnvironmentVariable = "DONWORRY_MERCHANT_HMAC_CAKESHOP";
}
