package com.cakeshop.domain.payment.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Toss 결제 설정의 활성 여부와 브라우저에 제공할 공개 키를 제공한다. */
@Component
public class TossPaymentAvailability {

    private final String clientKey;
    private final String secretKey;

    public TossPaymentAvailability(
            @Value("${app.payment.toss.client-key:}") String clientKey,
            @Value("${app.payment.toss.secret-key:}") String secretKey
    ) {
        this.clientKey = clientKey;
        this.secretKey = secretKey;
    }

    public String clientKey() {
        return clientKey;
    }

    public boolean isEnabled() {
        return hasText(clientKey) && hasText(secretKey);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
