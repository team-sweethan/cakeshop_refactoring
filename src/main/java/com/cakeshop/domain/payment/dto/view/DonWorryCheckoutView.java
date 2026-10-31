package com.cakeshop.domain.payment.dto.view;

import com.cakeshop.domain.payment.service.DonWorryDemoCard;
import java.util.List;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제창 화면 데이터
 * 설명 : 주문 요약은 기존 결제 화면과 같은 PaymentCheckoutView를 쓴다.
 * ******************************
 */
public record DonWorryCheckoutView(
        PaymentCheckoutView payment,
        List<DonWorryDemoCard> cards,
        String selectedCard,
        String idempotencyKey
) {
}
