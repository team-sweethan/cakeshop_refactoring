package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.mapper.PaymentOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-04
 * 기능 : 주문 도메인용 결제 조회 계약
 * 설명 : 주문 도메인이 payments 테이블을 직접 읽지 않고 결제 상태를 묻도록 제공한다(conventions 12절).
 *        결제 확인 중 판단 규칙(보류 시각은 있고 매입 시각은 없는 DONE 결제)은 결제 도메인이 소유한다.
 * ******************************
 */
@Service
@RequiredArgsConstructor
public class PaymentOrderQueryService {

    private final PaymentOrderMapper paymentOrderMapper;

    /**
     * PG(돈워리)가 주문의 결제를 아직 확인 중인지(매입 보류, 판정 전) 조회한다 (DonWorry ADR-0064 4절).
     * 확인 중이면 관리자 진행에 확인 창 동의가 필요하고 화면에 "결제 확인 중"을 보인다.
     */
    @Transactional(readOnly = true)
    public boolean isUnderGatewayReview(long orderId) {
        return orderId > 0 && paymentOrderMapper.existsPaymentUnderGatewayReview(orderId);
    }
}
