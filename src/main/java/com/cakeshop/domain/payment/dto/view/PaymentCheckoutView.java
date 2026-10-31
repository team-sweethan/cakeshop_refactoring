package com.cakeshop.domain.payment.dto.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** PG 결제창 요청에 필요한 서버 기준 주문·결제 정보다. */
public record PaymentCheckoutView(
        long orderId,
        String orderNumber,
        String gatewayOrderId,
        String orderName,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal amount,
        LocalDateTime pickupAt,
        LocalDateTime paymentExpiresAt,
        String customerName,
        String customerEmail,
        String customerPhone,
        String clientKey,
        boolean paymentAvailable,
        List<ItemView> items,
        // 결제에 고정된 PG가 OWN(DonWorry)이면 Toss 대신 DonWorry 결제창으로 보낸다.
        boolean donWorryCheckout
) {

    public record ItemView(
            String productName,
            int quantity,
            BigDecimal totalAmount,
            List<OptionView> options
    ) {
    }

    public record OptionView(String groupName, String optionName) {
    }
}
