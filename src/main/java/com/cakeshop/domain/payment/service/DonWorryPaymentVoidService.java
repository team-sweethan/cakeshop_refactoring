package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.OrderNotificationService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.GatewayVoidHandling;
import com.cakeshop.domain.order.service.payment.OrderPaymentRecoveryService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-02
 * 기능 : 돈워리 거절(VOIDED) 반영
 * 설명 : 카드 승인 뒤 보류된 결제를 돈워리 보안 직원이 거절하면 돈워리가 승인을 취소(VOIDED)한다.
 *        DonWorry ADR-0064 4-1에 따라 이행 전이면 주문을 취소하고(재고·쿠폰 복구, 고객 알림),
 *        이행(제작)이 시작됐으면 주문을 유지한다 — 손실은 PG(돈워리)가 부담한다. 유지한 결제는 한 번만 표시하고 기록한다.
 *        돈워리 쪽 승인은 이미 풀렸으므로 PG 취소 API를 부르지 않는다.
 * ******************************
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DonWorryPaymentVoidService {

    static final String REASON = "결제 보안 검토 결과 결제가 승인되지 않아 주문이 취소되었습니다.";

    private final PaymentMapper paymentMapper;
    private final OrderPaymentRecoveryService orderPaymentRecoveryService;
    private final OrderNotificationService orderNotificationService;
    private final OrderPaymentQueryService orderPaymentQueryService;

    public enum VoidOutcome {
        // 주문을 취소했다
        ORDER_CANCELED,
        // 이행이 시작돼 주문을 유지했다(처음 한 번)
        ORDER_KEPT,
        // 이미 반영했거나 할 일이 없다
        UNCHANGED
    }

    @Transactional
    public VoidOutcome applyVoid(Payment payment, long memberId, LocalDateTime canceledAt) {
        GatewayVoidHandling handling = orderPaymentQueryService.gatewayVoidHandling(payment.getOrderId())
                .orElse(GatewayVoidHandling.NOTHING);
        if (handling == GatewayVoidHandling.NOTHING) {
            return VoidOutcome.UNCHANGED;
        }
        if (handling == GatewayVoidHandling.KEEP_FULFILLED) {
            if (paymentMapper.markGatewayVoidKept(payment.getId(), payment.getPaymentKey()) != 1) {
                return VoidOutcome.UNCHANGED;
            }
            // 돈워리에 "이행됨"을 알리는 경로와 정산 처리는 DonWorry P6와 정하는 중이다. 지금은 관리자 확인용으로 남긴다
            log.warn("돈워리 승인 취소됐으나 이행된 주문이라 유지 orderId={} paymentId={}",
                    payment.getOrderId(), payment.getId());
            return VoidOutcome.ORDER_KEPT;
        }
        // DONE 결제에만 기록되므로 0이면 이미 반영된 거절이다(웹훅·조회 배치 중복).
        if (paymentMapper.insertGatewayVoidCancellation(
                payment.getId(), payment.getPaymentKey(), REASON, canceledAt) != 1) {
            return VoidOutcome.UNCHANGED;
        }
        if (paymentMapper.cancelPaymentByGatewayVoid(
                payment.getId(), payment.getPaymentKey(), canceledAt) != 1) {
            throw new IllegalStateException("돈워리 거절을 결제에 반영하지 못했습니다.");
        }
        // 픽업 완료 등 되돌릴 수 없는 주문이면 예외로 전체를 되돌리고 관리자 확인으로 넘긴다.
        orderPaymentRecoveryService.cancelAfterPaymentCompensation(payment.getOrderId(), canceledAt, REASON);
        orderNotificationService.notifyOrderCanceled(payment.getOrderId(), memberId);
        return VoidOutcome.ORDER_CANCELED;
    }
}
