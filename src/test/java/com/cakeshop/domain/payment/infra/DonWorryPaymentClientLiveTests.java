package com.cakeshop.domain.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 중인 DonWorry에 실제로 서명 요청을 보낸다. DONWORRY_LIVE_BASE_URL과 DonWorry와 같은
 * DONWORRY_MERCHANT_HMAC_CAKESHOP이 OS 환경변수로 있을 때만 실행하며 CI에서는 건너뛴다.
 */
@EnabledIfEnvironmentVariable(named = "DONWORRY_LIVE_BASE_URL", matches = "https?://.+")
class DonWorryPaymentClientLiveTests {

    // 2026-11-02: CakeShop은 결제를 직접 요청하지 않고 돈워리 결제창을 준비한다(DonWorry ADR-0064). 결제 준비를 실제로 보낸다
    @Test
    void createSession_againstRunningDonWorry() {
        DonWorryPaymentProperties properties = new DonWorryPaymentProperties();
        properties.setBaseUrl(URI.create(System.getenv("DONWORRY_LIVE_BASE_URL")));
        DonWorryPaymentClient client =
                new DonWorryPaymentClient(properties, JsonMapper.builder().build(), Clock.systemUTC());
        String orderRef = "LIVE-" + UUID.randomUUID();
        long memberId = 900_000_000L + (long) (Math.random() * 99_999_999L);
        DonWorryPaymentSessionRequest request = new DonWorryPaymentSessionRequest(
                orderRef, String.valueOf(memberId), 25_000L, Instant.now().minusSeconds(1), "라이브 확인 주문", null,
                "http://127.0.0.1:8080/orders/1/payment/donworry/return",
                "http://127.0.0.1:8080/orders/1/payment/fail", null);
        String idempotencyKey = UUID.randomUUID().toString();

        DonWorryPaymentSessionResponse created = client.createSession(request, idempotencyKey);
        DonWorryPaymentSessionResponse resent = client.createSession(request, idempotencyKey);

        assertThat(created.checkoutUrl()).contains("/checkout/" + created.sessionKey());
        // 같은 키 · 같은 본문 재전송은 새 세션을 만들지 않는다.
        assertThat(resent.sessionKey()).isEqualTo(created.sessionKey());
        System.out.println("DonWorry live: sessionKey=" + created.sessionKey() + " checkoutUrl=" + created.checkoutUrl());
    }
}
