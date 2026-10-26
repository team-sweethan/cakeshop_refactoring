package com.cakeshop.domain.payment.dto.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** PG 결제 인증 성공 후 서버 승인에 필요한 값을 전달한다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentConfirmForm {

    @NotBlank
    @Size(max = 200)
    private String paymentKey;

    @NotBlank
    @Size(min = 6, max = 64)
    private String gatewayOrderId;

    @NotNull
    @Positive
    private BigDecimal amount;
}
