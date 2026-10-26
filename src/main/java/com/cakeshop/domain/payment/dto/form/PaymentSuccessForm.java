package com.cakeshop.domain.payment.dto.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** PG 성공 URL에 추가되는 인증 결과를 받는다. */
@Getter
@Setter
public class PaymentSuccessForm {

    @NotBlank
    @Size(max = 200)
    private String paymentKey;

    @NotBlank
    @Size(min = 6, max = 64)
    private String orderId;

    @NotNull
    @Positive
    private BigDecimal amount;

    public PaymentConfirmForm toConfirmForm() {
        return new PaymentConfirmForm(paymentKey, orderId, amount);
    }
}
