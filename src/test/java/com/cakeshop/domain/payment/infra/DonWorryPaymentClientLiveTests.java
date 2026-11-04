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

    @Test
    void requestAndFind_againstRunningDonWorry() {
        DonWorryPaymentProperties properties = new DonWorryPaymentProperties();
        properties.setBaseUrl(URI.create(System.getenv("DONWORRY_LIVE_BASE_URL")));
        DonWorryPaymentClient client =
                new DonWorryPaymentClient(properties, JsonMapper.builder().build(), Clock.systemUTC());
        String orderRef = "LIVE-" + UUID.randomUUID();
        long memberId = 900_000_000L + (long) (Math.random() * 99_999_999L);
        DonWorryPaymentRequest request =
                DonWorryPaymentRequest.of(orderRef, memberId, 25_000L, Instant.now().minusSeconds(1));
        String idempotencyKey = UUID.randomUUID().toString();

        DonWorryPaymentResponse created = client.request(request, idempotencyKey);
        DonWorryPaymentResponse resent = client.request(request, idempotencyKey);
        DonWorryPaymentResponse found = client.find(created.paymentId());

        assertThat(created.orderRef()).isEqualTo(orderRef);
        assertThat(created.amount()).isEqualTo(25_000L);
        // 같은 키 재전송은 새 결제를 만들지 않는다.
        assertThat(resent.paymentId()).isEqualTo(created.paymentId());
        assertThat(found.paymentId()).isEqualTo(created.paymentId());
        System.out.println("DonWorry live: paymentId=" + found.paymentId() + " status=" + found.paymentStatus());
    }
}
