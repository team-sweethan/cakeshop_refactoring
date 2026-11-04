package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
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
 *        - CAPTURED(보류 뒤 직원 승인)인데 CakeShop은 "결제 확인 중" → 확인 중을 끝내고 일반 주문은 픽업 준비로
 *        - VOIDED(보류 뒤 직원 거절)인데 CakeShop은 DONE → 주문 취소 · 재고·쿠폰 복구 · 고객 알림
 *        - 승인됐는데 CakeShop 결제 기한이 지남(READY 완료가 PAYMENT_EXPIRED, 또는 이미 EXPIRED) → 늦은 승인.
 *          기존 보상 취소(SYSTEM_COMPENSATION)로 돈워리 승인을 취소한다. 취소가 바로 안 끝나면 복구 배치가 다시 보낸다
 *          (pg-decisions 008).
 *        그 밖의 상태(매입 대기·보류 중)는 바꿀 것이 없다.
 * ******************************
 */
@Slf4j
@Service
public class DonWorryPaymentSyncService {

    private static final Duration SYNC_WINDOW = Duration.ofDays(3);
    // 만료된 결제의 늦은 승인을 조회 배치로 확인하는 기간(결제 생성 기준). 웹훅이 닿지 않는 환경의 복구용이다.
    private static final Duration LATE_APPROVAL_WINDOW = Duration.ofHours(1);

    private final ObjectProvider<DonWorryPaymentClient> clientProvider;
    private final PaymentMapper paymentMapper;
    private final OrderPaymentQueryService orderPaymentQueryService;
    private final PaymentFacade paymentFacade;
    private final DonWorryPaymentVoidService voidService;
    private final DonWorryPaymentCaptureService captureService;
    private final PaymentCompensationProcessor compensationProcessor;
    private final Clock clock;

    public DonWorryPaymentSyncService(
            ObjectProvider<DonWorryPaymentClient> clientProvider,
            PaymentMapper paymentMapper,
            OrderPaymentQueryService orderPaymentQueryService,
            PaymentFacade paymentFacade,
            DonWorryPaymentVoidService voidService,
            DonWorryPaymentCaptureService captureService,
            PaymentCompensationProcessor compensationProcessor,
            Clock clock
    ) {
        this.clientProvider = clientProvider;
        this.paymentMapper = paymentMapper;
        this.orderPaymentQueryService = orderPaymentQueryService;
        this.paymentFacade = paymentFacade;
        this.voidService = voidService;
        this.captureService = captureService;
        this.compensationProcessor = compensationProcessor;
        this.clock = clock;
    }

    // CAPTURED: 보류 뒤 승인돼 결제 확인 중을 끝냄(DonWorry ADR-0064 4절)
    // VOIDED: 거절로 주문 취소, FULFILLED_KEPT: 거절됐지만 가맹점이 이미 진행한 주문이라 유지(손실은 가맹점 부담, 4-1)
    // LATE_APPROVAL_CANCELED: 결제 기한 뒤 승인을 돈워리에서 취소함, LATE_APPROVAL_CANCEL_PENDING: 취소 요청을 남겨 복구 배치가 다시 보냄
    public enum SyncResult {
        COMPLETED, CAPTURED, VOIDED, FULFILLED_KEPT, LATE_APPROVAL_CANCELED, LATE_APPROVAL_CANCEL_PENDING,
        UNCHANGED, NOT_FOUND
    }

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
                if (exception.getErrorCode() == PaymentErrorCode.PAYMENT_EXPIRED) {
                    // 결제창에서 이미 승인됐지만 CakeShop 결제 기한이 지났다. 청구가 남지 않게 승인을 취소한다.
                    return cancelLateApproval(payment, paymentKey);
                }
                if (exception.getErrorCode() == PaymentErrorCode.PAYMENT_COMPENSATED) {
                    // 승인 확인 중에 기한을 넘겨 결제 흐름이 이미 보상 취소를 마쳤다.
                    return SyncResult.LATE_APPROVAL_CANCELED;
                }
                // 다른 결제가 먼저 완료된 경우 등. 자동으로 바꾸지 않고 관리자 확인용 경고만 남긴다.
                log.warn("donworry sync could not complete orderId={} code={}",
                        payment.getOrderId(), exception.getErrorCode());
                return SyncResult.UNCHANGED;
            }
        }
        if (response.approved() && payment.getStatus() == PaymentStatus.EXPIRED) {
            // 만료 배치가 주문 · 결제를 먼저 만료시킨 뒤 승인이 확인됐다.
            return cancelLateApproval(payment, paymentKey);
        }
        if (response.captured()
                && payment.getStatus() == PaymentStatus.DONE
                && paymentKey.equals(payment.getPaymentKey())) {
            return captureService.applyCapture(payment, LocalDateTime.now(clock))
                    ? SyncResult.CAPTURED
                    : SyncResult.UNCHANGED;
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

    /**
     * 결제 기한 뒤 늦게 승인된 돈워리 결제를 기존 보상 취소로 취소한다(pg-decisions 008).
     * 보상 요청(COMPENSATE-{결제 ID})을 먼저 저장하므로 만료 배치가 건너뛰고, 취소가 바로 끝나지 않으면
     * 복구 배치가 같은 키로 다시 보낸다. 보류 · 매입 대기는 승인 취소, 매입된 결제는 돈워리 환불 연결 전까지 대기한다.
     */
    private SyncResult cancelLateApproval(Payment payment, String paymentKey) {
        log.warn("donworry late approval: cancel requested orderId={} paymentId={}",
                payment.getOrderId(), payment.getId());
        try {
            BusinessException outcome = compensationProcessor.compensateApproved(payment, paymentKey);
            if (outcome.getErrorCode() == PaymentErrorCode.PAYMENT_COMPENSATED) {
                return SyncResult.LATE_APPROVAL_CANCELED;
            }
        } catch (BusinessException exception) {
            log.warn("donworry late approval cancel pending orderId={} code={}",
                    payment.getOrderId(), exception.getErrorCode());
        }
        return SyncResult.LATE_APPROVAL_CANCEL_PENDING;
    }

    /**
     * 조회 배치: 웹훅을 놓쳐도 돈워리 결제의 승인·판정을 맞춘다. 바뀐 건수를 돌려준다.
     * 아직 바뀔 수 있는 결제만 본다 — 판정 대기(보류 중) 결제는 기간과 관계없이, 결제창을 닫아 READY인 결제는 최근 3일,
     * 보상 요청 없이 만료된 결제는 생성 후 1시간(늦은 승인 확인).
     * 이미 끝난 정상 결제는 다시 묻지 않아 오래된 판정 대기 결제가 최신 결제에 밀려 빠지지 않는다.
     */
    public int syncRecent(int limit) {
        DonWorryPaymentClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return 0;
        }
        int changed = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minus(SYNC_WINDOW);
        LocalDateTime expiredSince = now.minus(LATE_APPROVAL_WINDOW);
        for (String gatewayOrderId : paymentMapper.findDonWorryGatewayOrderIdsToSync(since, expiredSince, limit)) {
            try {
                // 주문 참조로 최신 시도를 찾는다. 결제창을 열지 않은 주문은 시도가 없어 건너뛴다.
                Optional<DonWorryPaymentResponse> latest = client.findLatestAttempt(gatewayOrderId);
                if (latest.isEmpty()) {
                    continue;
                }
                SyncResult result = sync(latest.get().paymentId());
                if (result == SyncResult.COMPLETED || result == SyncResult.CAPTURED
                        || result == SyncResult.VOIDED || result == SyncResult.FULFILLED_KEPT
                        || result == SyncResult.LATE_APPROVAL_CANCELED) {
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
