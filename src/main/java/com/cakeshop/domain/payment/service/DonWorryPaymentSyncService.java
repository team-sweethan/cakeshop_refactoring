package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentGateway;
import com.cakeshop.domain.payment.infra.DonWorryPaymentResponse;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-02
 * 기능 : 돈워리 결제 상태를 CakeShop 결제·주문에 맞춘다 (웹훅 · 조회 배치 공통)
 * 설명 : 웹훅 본문은 믿지 않고 돈워리에 결제를 다시 조회해 반영한다.
 *        - 승인됐는데 CakeShop은 아직 READY(구매자가 결제창을 닫음 등) → 기존 승인 확인 흐름으로 결제 완료
 *        - VOIDED(보류 뒤 직원 거절)인데 CakeShop은 DONE → 주문 취소 · 재고·쿠폰 복구 · 고객 알림
 *        그 밖의 상태(매입 대기·매입 완료·보류 중)는 바꿀 것이 없다.
 * ******************************
 */
@Slf4j
@Service
public class DonWorryPaymentSyncService {

    private static final Duration SYNC_WINDOW = Duration.ofDays(3);

    private final ObjectProvider<DonWorryPaymentClient> clientProvider;
    private final PaymentMapper paymentMapper;
    private final OrderPaymentQueryService orderPaymentQueryService;
    private final PaymentFacade paymentFacade;
    private final DonWorryPaymentVoidService voidService;
    private final Clock clock;

    public DonWorryPaymentSyncService(
            ObjectProvider<DonWorryPaymentClient> clientProvider,
            PaymentMapper paymentMapper,
            OrderPaymentQueryService orderPaymentQueryService,
            PaymentFacade paymentFacade,
            DonWorryPaymentVoidService voidService,
            Clock clock
    ) {
        this.clientProvider = clientProvider;
        this.paymentMapper = paymentMapper;
        this.orderPaymentQueryService = orderPaymentQueryService;
        this.paymentFacade = paymentFacade;
        this.voidService = voidService;
        this.clock = clock;
    }

    // VOIDED: 거절로 주문 취소, FULFILLED_KEPT: 거절됐지만 이행된 주문이라 유지(DonWorry ADR-0064 4-1)
    public enum SyncResult { COMPLETED, VOIDED, FULFILLED_KEPT, UNCHANGED, NOT_FOUND }

    /** 돈워리 결제 한 건을 다시 조회해 반영한다. 돈워리 조회 실패는 예외로 알려 웹훅 재전송을 받는다. */
    public SyncResult sync(long donWorryPaymentId) {
        DonWorryPaymentClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return SyncResult.NOT_FOUND;
        }
        DonWorryPaymentResponse response;
        try {
            response = client.find(donWorryPaymentId);
        } catch (DonWorryPaymentException exception) {
            if (exception.getStatus() == 404) {
                return SyncResult.NOT_FOUND;
            }
            throw exception;
        }
        Optional<Payment> found = paymentMapper.findPaymentByGatewayOrderId(response.orderRef());
        if (found.isEmpty()
                || found.get().getProvider() != PaymentProvider.OWN
                || found.get().getAmount() == null
                || found.get().getAmount().compareTo(BigDecimal.valueOf(response.amount())) != 0) {
            log.warn("donworry sync skipped: unknown order donWorryPaymentId={}", donWorryPaymentId);
            return SyncResult.NOT_FOUND;
        }
        Payment payment = found.get();
        Long memberId = orderPaymentQueryService.findOrderMemberId(payment.getOrderId()).orElse(null);
        if (memberId == null) {
            return SyncResult.NOT_FOUND;
        }
        String paymentKey = DonWorryPaymentGateway.paymentKey(response.paymentId());

        if (response.approved() && payment.getStatus() == PaymentStatus.READY) {
            try {
                paymentFacade.confirmPayment(memberId, payment.getOrderId(),
                        new PaymentConfirmForm(paymentKey, payment.getGatewayOrderId(), payment.getAmount()));
                return SyncResult.COMPLETED;
            } catch (BusinessException exception) {
                // 결제 기한이 지났거나 다른 결제가 먼저 완료된 경우. 돈워리에서는 승인돼 청구가 남아 있을 수 있지만
                // 지금은 자동으로 취소하지 않고 경고만 남긴다. 누가 취소할지는 아직 정하지 않았다
                // (DonWorry ADR-0064 남은 결정 "결제 접수 기한이 지난 뒤 늦게 승인된 결제").
                log.warn("donworry sync could not complete orderId={} code={}",
                        payment.getOrderId(), exception.getErrorCode());
                return SyncResult.UNCHANGED;
            }
        }
        if ("VOIDED".equals(response.paymentStatus())
                && payment.getStatus() == PaymentStatus.DONE
                && paymentKey.equals(payment.getPaymentKey())) {
            return switch (voidService.applyVoid(payment, memberId, LocalDateTime.now(clock))) {
                case ORDER_CANCELED -> SyncResult.VOIDED;
                case ORDER_KEPT -> SyncResult.FULFILLED_KEPT;
                case UNCHANGED -> SyncResult.UNCHANGED;
            };
        }
        return SyncResult.UNCHANGED;
    }

    /** 조회 배치: 웹훅을 놓쳐도 최근 돈워리 결제의 승인·거절을 맞춘다. 바뀐 건수를 돌려준다. */
    public int syncRecent(int limit) {
        DonWorryPaymentClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return 0;
        }
        int changed = 0;
        LocalDateTime since = LocalDateTime.now(clock).minus(SYNC_WINDOW);
        for (String gatewayOrderId : paymentMapper.findDonWorryGatewayOrderIdsToSync(since, limit)) {
            try {
                // 주문 참조로 최신 시도를 찾는다. 결제창을 열지 않은 주문은 시도가 없어 건너뛴다.
                Optional<DonWorryPaymentResponse> latest = client.findLatestAttempt(gatewayOrderId);
                if (latest.isEmpty()) {
                    continue;
                }
                SyncResult result = sync(latest.get().paymentId());
                if (result == SyncResult.COMPLETED || result == SyncResult.VOIDED
                        || result == SyncResult.FULFILLED_KEPT) {
                    changed++;
                }
            } catch (RuntimeException exception) {
                log.warn("donworry sync failed gatewayOrderId={} reason={}",
                        gatewayOrderId, exception.getClass().getSimpleName());
            }
        }
        return changed;
    }
}
