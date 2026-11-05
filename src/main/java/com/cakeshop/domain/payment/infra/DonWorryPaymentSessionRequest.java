package com.cakeshop.domain.payment.infra;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : 돈워리 결제 준비(POST /api/payment-sessions) 요청 본문
 * 설명 : 금액·주문번호·고객 참조·주문 시각 규칙은 기존 결제 요청과 같다. 카드·기기·IP는 보내지 않고 돈워리 결제창이 직접 받는다.
 *        2026-11-04: 주문자·픽업자 이름과 전화번호를 함께 보낸다(돈워리 고객정보 접근 보안 시나리오, P4 수신).
 *        전화번호는 CakeShop 저장 형식(하이픈 포함) 그대로다. 값이 없으면 JSON에서 빠진다.
 * ******************************
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DonWorryPaymentSessionRequest(
        String orderRef,
        String customerRef,
        long amount,
        Instant orderCreatedAt,
        String orderName,
        String buyerEmail,
        String successUrl,
        String failUrl,
        String notifyUrl,
        String ordererName,
        String ordererPhone,
        String pickupName,
        String pickupPhone
) {

    public DonWorryPaymentSessionRequest {
        // DonWorry는 마이크로초보다 정밀한 시각을 거절한다.
        if (orderCreatedAt != null) {
            orderCreatedAt = orderCreatedAt.truncatedTo(ChronoUnit.MICROS);
        }
        if (orderName != null && orderName.length() > 100) {
            orderName = orderName.substring(0, 100);
        }
        ordererName = blankToNull(ordererName);
        ordererPhone = blankToNull(ordererPhone);
        pickupName = blankToNull(pickupName);
        pickupPhone = blankToNull(pickupPhone);
    }

    /** 주문자·픽업자 정보 없이 보내는 요청(실서버 연결 확인 등) */
    public DonWorryPaymentSessionRequest(
            String orderRef,
            String customerRef,
            long amount,
            Instant orderCreatedAt,
            String orderName,
            String buyerEmail,
            String successUrl,
            String failUrl,
            String notifyUrl
    ) {
        this(orderRef, customerRef, amount, orderCreatedAt, orderName, buyerEmail, successUrl, failUrl, notifyUrl,
                null, null, null, null);
    }

    // 빈 값은 보내지 않는다. 픽업이 없는 주문과 같게 다룬다.
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
