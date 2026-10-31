package com.cakeshop.domain.payment.dto.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제창 제출 값
 * 설명 : idempotencyKey는 결제창을 열 때 서버가 만든 UUID다. 같은 창에서 다시 제출하면 같은 결제 시도로 처리된다.
 * ******************************
 */
@Getter
@Setter
public class DonWorryPayForm {

    @NotBlank
    @Pattern(regexp = "[A-Z]")
    private String card;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private String idempotencyKey;
}
