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
        // 지금 결제에 고정된 PG가 OWN(DonWorry)인지. DonWorry 결제창은 OWN일 때만 열린다.
        boolean donWorryCheckout,
        // 결제 수단 선택에서 고를 수 있는 PG. Toss는 키가 있어야, DonWorry는 연동이 켜져 있어야 한다.
        boolean tossAvailable,
        boolean donWorryAvailable,
        // 결제창 선택에서 Toss를 고를 때 쓰는 키. 저장된 PG가 OWN이어도 Toss 결제창을 열 수 있어야 한다.
        String tossClientKey
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
