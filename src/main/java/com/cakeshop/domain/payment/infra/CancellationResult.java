package com.cakeshop.domain.payment.infra;

import java.time.LocalDateTime;

public record CancellationResult(
        PaymentGatewayStatus status,
        String transactionKey,
        LocalDateTime canceledAt
) {
}
