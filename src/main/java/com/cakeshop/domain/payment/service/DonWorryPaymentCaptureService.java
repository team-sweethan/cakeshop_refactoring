package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentCommandService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-04
 * 기능 : 돈워리 보류(HELD) 결제의 직원 승인(CAPTURED) 반영
 * 설명 : 돈워리가 매입을 보류한 결제는 가맹점에서 "결제 확인 중"이다(DonWorry ADR-0064 4절).
 *        직원이 승인해 매입되면 확인 중을 끝내고, 아직 주문 확인 중인 일반 주문은 픽업 준비로 넘긴다.
 *        수제 주문은 원래 관리자 검토를 거치므로 상태를 바꾸지 않는다.
 *        가맹점이 판정 전에 먼저 진행한 주문(이미 픽업 준비 등)도 상태는 그대로 두고 표시만 끝낸다.
 * ******************************
 */
@Service
@RequiredArgsConstructor
public class DonWorryPaymentCaptureService {

    private final PaymentMapper paymentMapper;
    private final OrderPaymentCommandService orderPaymentCommandService;

    /**
     * @return 이번에 확인 중을 끝냈으면 true. 보류가 아니었거나 이미 반영했으면(웹훅·조회 배치 중복) false
     */
    @Transactional
    public boolean applyCapture(Payment payment, LocalDateTime capturedAt) {
        if (paymentMapper.markGatewayCaptured(payment.getId(), payment.getPaymentKey(), capturedAt) != 1) {
            return false;
        }
        orderPaymentCommandService.releaseGeneralOrderAfterGatewayCapture(payment.getOrderId(), capturedAt);
        return true;
    }
}
