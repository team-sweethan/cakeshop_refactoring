package com.cakeshop.domain.payment.infra;

import lombok.Getter;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제 API 호출 실패
 * 설명 : 응답을 받지 못했으면 status는 0이다. 이때는 결제가 생겼는지 모르므로 같은 Idempotency-Key로 다시 보낸다.
 *        code는 DonWorry ProblemDetail의 code이며, 본문·서명·키는 담지 않는다.
 * ******************************
 */
@Getter
public class DonWorryPaymentException extends RuntimeException {

    private final int status;
    private final String code;

    public DonWorryPaymentException(int status, String code) {
        super("DonWorry 결제 API 호출 실패: status=" + status + (code == null ? "" : ", code=" + code));
        this.status = status;
        this.code = code;
    }

    /** 응답을 받지 못해 결제 생성 여부를 알 수 없는 경우다. */
    public boolean resultUnknown() {
        return status == 0 || status >= 500;
    }
}
