package com.cakeshop.domain.payment.dto.view;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제 결과 대기 화면 데이터
 * 설명 : PENDING·HELD처럼 아직 결과가 나지 않은 결제만 이 화면에 머문다.
 * ******************************
 */
public record DonWorryPaymentStatusView(
        long orderId,
        String orderNumber,
        String paymentKey,
        String paymentStatus
) {

    public boolean held() {
        return "HELD".equals(paymentStatus);
    }
}
