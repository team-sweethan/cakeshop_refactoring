package com.cakeshop.domain.payment.error;

import com.cakeshop.global.error.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {

    AMOUNT_MISMATCH("PAYMENT_001", "결제 금액이 일치하지 않습니다.", 400),
    READY_PAYMENT_NOT_FOUND("PAYMENT_002", "결제 가능한 결제 시도를 찾을 수 없습니다.", 409),
    PAYMENT_ORDER_ID_MISMATCH("PAYMENT_003", "결제 주문번호가 일치하지 않습니다.", 400),
    PAYMENT_EXPIRED("PAYMENT_004", "결제 가능 시간이 지났습니다.", 409),
    PAYMENT_APPROVAL_FAILED("PAYMENT_005", "결제 승인에 실패했습니다.", 502),
    PAYMENT_COMPLETE_FAILED("PAYMENT_006", "결제 완료 처리에 실패했습니다.", 500),
    PAYMENT_PREPARATION_FAILED("PAYMENT_007", "결제 준비 저장에 실패했습니다.", 500),
    PAYMENT_CANCEL_NOT_AVAILABLE("PAYMENT_008", "현재 취소할 수 없는 주문입니다.", 409),
    PAYMENT_CANCEL_FAILED("PAYMENT_009", "결제 취소에 실패했습니다.", 502),
    PAYMENT_CANCEL_COMPLETE_FAILED("PAYMENT_010", "결제 취소 완료 처리에 실패했습니다.", 500),
    COMPLETED_PAYMENT_NOT_FOUND("PAYMENT_011", "완료된 결제를 찾을 수 없습니다.", 409),
    PAYMENT_STATUS_LOOKUP_FAILED("PAYMENT_012", "결제 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", 503),
    PAYMENT_RECOVERY_PENDING("PAYMENT_013", "결제 복구를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.", 503),
    PAYMENT_COMPENSATED("PAYMENT_014", "주문 처리 실패로 승인된 결제를 자동 취소했습니다. 다시 주문해 주세요.", 409),

    PAYMENT_GATEWAY_UNAVAILABLE("PAYMENT_015", "해당 결제사의 처리를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503);

    private final String code;
    private final String message;
    private final int status;

    PaymentErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override public String code() { return code; }
    @Override public String message() { return message; }
    @Override public int status() { return status; }
}
