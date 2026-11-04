package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentResponse;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.service.DonWorryPaymentSyncService.SyncResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DonWorryPaymentSyncServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final DonWorryPaymentClient client = mock(DonWorryPaymentClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DonWorryPaymentClient> clientProvider = mock(ObjectProvider.class);
    private final PaymentMapper paymentMapper = mock(PaymentMapper.class);
    private final OrderPaymentQueryService orderPaymentQueryService = mock(OrderPaymentQueryService.class);
    private final PaymentFacade paymentFacade = mock(PaymentFacade.class);
    private final DonWorryPaymentVoidService voidService = mock(DonWorryPaymentVoidService.class);
    private final DonWorryPaymentCaptureService captureService = mock(DonWorryPaymentCaptureService.class);
    private final PaymentCompensationProcessor compensationProcessor = mock(PaymentCompensationProcessor.class);
    private final DonWorryPaymentSyncService service = new DonWorryPaymentSyncService(
            clientProvider, paymentMapper, orderPaymentQueryService, paymentFacade, voidService, captureService,
            compensationProcessor, CLOCK);

    @BeforeEach
    void setUp() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(orderPaymentQueryService.findOrderMemberId(7L)).thenReturn(Optional.of(10L));
    }

    @Test
    void sync_authorizedWhileShopStillReady_completesThroughExistingConfirm() {
        when(client.find(101L)).thenReturn(donWorry("PENDING", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(local(PaymentStatus.READY, null)));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.COMPLETED);

        verify(paymentFacade).confirmPayment(eq(10L), eq(7L), argThat(form ->
                form.getPaymentKey().equals("DW-101")
                        && form.getGatewayOrderId().equals("ORD-100")
                        && form.getAmount().compareTo(BigDecimal.valueOf(52_000)) == 0));
    }

    // 결제창에서 승인됐지만 CakeShop 결제 기한이 지나 완료할 수 없으면 승인을 보상 취소한다 (pg-decisions 008)
    @Test
    void sync_approvedButShopPaymentExpired_cancelsLateApproval() {
        Payment ready = local(PaymentStatus.READY, null);
        when(client.find(101L)).thenReturn(donWorry("CAPTURED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(ready));
        org.mockito.Mockito.doThrow(new BusinessException(PaymentErrorCode.PAYMENT_EXPIRED))
                .when(paymentFacade).confirmPayment(anyLong(), anyLong(), any());
        when(compensationProcessor.compensateApproved(ready, "DW-101"))
                .thenReturn(new BusinessException(PaymentErrorCode.PAYMENT_COMPENSATED));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.LATE_APPROVAL_CANCELED);
        verify(compensationProcessor).compensateApproved(ready, "DW-101");
    }

    @Test
    void sync_approvedAfterShopPaymentAlreadyExpired_cancelsLateApproval() {
        Payment expired = local(PaymentStatus.EXPIRED, null);
        when(client.find(101L)).thenReturn(donWorry("HELD", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(expired));
        when(compensationProcessor.compensateApproved(expired, "DW-101"))
                .thenReturn(new BusinessException(PaymentErrorCode.PAYMENT_COMPENSATED));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.LATE_APPROVAL_CANCELED);
        verify(paymentFacade, never()).confirmPayment(anyLong(), anyLong(), any());
    }

    // 취소가 바로 끝나지 않으면(매입 결제 환불 503 등) 보상 요청을 남기고 복구 배치에 맡긴다
    @Test
    void sync_lateApprovalCancelNotFinished_isPending() {
        Payment expired = local(PaymentStatus.EXPIRED, null);
        when(client.find(101L)).thenReturn(donWorry("CAPTURED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(expired));
        when(compensationProcessor.compensateApproved(expired, "DW-101"))
                .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING))
                .thenReturn(new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.LATE_APPROVAL_CANCEL_PENDING);
        assertThat(service.sync(101L)).isEqualTo(SyncResult.LATE_APPROVAL_CANCEL_PENDING);
    }

    // 승인되지 않은 시도나 다른 이유의 완료 실패는 취소하지 않는다
    @Test
    void sync_expiredWithoutApprovalOrOtherFailure_doesNotCancel() {
        when(client.find(101L)).thenReturn(donWorry("DENIED", false), donWorry("PENDING", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100"))
                .thenReturn(Optional.of(local(PaymentStatus.EXPIRED, null)), Optional.of(local(PaymentStatus.READY, null)));
        org.mockito.Mockito.doThrow(new BusinessException(PaymentErrorCode.READY_PAYMENT_NOT_FOUND))
                .when(paymentFacade).confirmPayment(anyLong(), anyLong(), any());

        assertThat(service.sync(101L)).isEqualTo(SyncResult.UNCHANGED);
        assertThat(service.sync(101L)).isEqualTo(SyncResult.UNCHANGED);
        verify(compensationProcessor, never()).compensateApproved(any(), any());
    }

    @Test
    void sync_confirmAlreadyCompensated_isLateApprovalCanceled() {
        when(client.find(101L)).thenReturn(donWorry("PENDING", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(local(PaymentStatus.READY, null)));
        org.mockito.Mockito.doThrow(new BusinessException(PaymentErrorCode.PAYMENT_COMPENSATED))
                .when(paymentFacade).confirmPayment(anyLong(), anyLong(), any());

        assertThat(service.sync(101L)).isEqualTo(SyncResult.LATE_APPROVAL_CANCELED);
        verify(compensationProcessor, never()).compensateApproved(any(), any());
    }

    @Test
    void sync_voidedAfterShopCompleted_cancelsOrder() {
        Payment done = local(PaymentStatus.DONE, "DW-101");
        when(client.find(101L)).thenReturn(donWorry("VOIDED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(done));
        when(voidService.applyVoid(eq(done), eq(10L), any())).thenReturn(DonWorryPaymentVoidService.VoidOutcome.ORDER_CANCELED);

        assertThat(service.sync(101L)).isEqualTo(SyncResult.VOIDED);
    }

    // 이행(제작)이 시작된 주문은 거절돼도 유지한다 — 결과를 따로 알린다 (DonWorry ADR-0064 4-1)
    @Test
    void sync_voidedAfterFulfillmentStarted_keepsOrder() {
        Payment done = local(PaymentStatus.DONE, "DW-101");
        when(client.find(101L)).thenReturn(donWorry("VOIDED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(done));
        when(voidService.applyVoid(eq(done), eq(10L), any()))
                .thenReturn(DonWorryPaymentVoidService.VoidOutcome.ORDER_KEPT)
                .thenReturn(DonWorryPaymentVoidService.VoidOutcome.UNCHANGED);

        assertThat(service.sync(101L)).isEqualTo(SyncResult.FULFILLED_KEPT);
        assertThat(service.sync(101L)).isEqualTo(SyncResult.UNCHANGED);
    }

    @Test
    void sync_heldAfterShopCompleted_changesNothing() {
        when(client.find(101L)).thenReturn(donWorry("HELD", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100"))
                .thenReturn(Optional.of(local(PaymentStatus.DONE, "DW-101")));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.UNCHANGED);

        verify(paymentFacade, never()).confirmPayment(anyLong(), anyLong(), any());
        verify(voidService, never()).applyVoid(any(), anyLong(), any());
        verify(captureService, never()).applyCapture(any(), any());
    }

    // 보류 뒤 직원 승인(CAPTURED)은 "결제 확인 중"을 한 번만 끝낸다 (DonWorry ADR-0064 4절)
    @Test
    void sync_capturedAfterShopCompleted_endsReviewOnce() {
        Payment done = local(PaymentStatus.DONE, "DW-101");
        when(client.find(101L)).thenReturn(donWorry("CAPTURED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(done));
        when(captureService.applyCapture(eq(done), any())).thenReturn(true).thenReturn(false);

        assertThat(service.sync(101L)).isEqualTo(SyncResult.CAPTURED);
        assertThat(service.sync(101L)).isEqualTo(SyncResult.UNCHANGED);
        verify(voidService, never()).applyVoid(any(), anyLong(), any());
    }

    @Test
    void sync_captureOfOtherAttempt_isIgnored() {
        when(client.find(101L)).thenReturn(donWorry("CAPTURED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100"))
                .thenReturn(Optional.of(local(PaymentStatus.DONE, "DW-100")));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.UNCHANGED);
        verify(captureService, never()).applyCapture(any(), any());
    }

    @Test
    void sync_voidOfOtherAttempt_doesNotCancelCompletedPayment() {
        // CakeShop이 완료한 결제(DW-100)와 다른 시도(DW-101)가 거절된 경우
        when(client.find(101L)).thenReturn(donWorry("VOIDED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100"))
                .thenReturn(Optional.of(local(PaymentStatus.DONE, "DW-100")));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.UNCHANGED);
        verify(voidService, never()).applyVoid(any(), anyLong(), any());
    }

    @Test
    void sync_amountMismatchOrUnknownOrder_isNotFound() {
        when(client.find(101L)).thenReturn(donWorry("VOIDED", true));
        Payment other = local(PaymentStatus.DONE, "DW-101");
        other.setAmount(BigDecimal.valueOf(1_000));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(other), Optional.empty());

        assertThat(service.sync(101L)).isEqualTo(SyncResult.NOT_FOUND);
        assertThat(service.sync(101L)).isEqualTo(SyncResult.NOT_FOUND);
        verify(voidService, never()).applyVoid(any(), anyLong(), any());
    }

    @Test
    void sync_donWorryNotFound_isNotFound() {
        when(client.find(101L)).thenThrow(new DonWorryPaymentException(404, "PAYMENT_NOT_FOUND"));

        assertThat(service.sync(101L)).isEqualTo(SyncResult.NOT_FOUND);
    }

    @Test
    void syncRecent_looksUpLatestAttemptByOrderAndCountsChanges() {
        Payment done = local(PaymentStatus.DONE, "DW-101");
        when(paymentMapper.findDonWorryGatewayOrderIdsToSync(any(), any(), eq(50))).thenReturn(List.of("ORD-100", "ORD-200"));
        when(client.findLatestAttempt("ORD-100")).thenReturn(Optional.of(donWorry("VOIDED", true)));
        when(client.findLatestAttempt("ORD-200")).thenReturn(Optional.empty());
        when(client.find(101L)).thenReturn(donWorry("VOIDED", true));
        when(paymentMapper.findPaymentByGatewayOrderId("ORD-100")).thenReturn(Optional.of(done));
        when(voidService.applyVoid(eq(done), eq(10L), any())).thenReturn(DonWorryPaymentVoidService.VoidOutcome.ORDER_CANCELED);

        assertThat(service.syncRecent(50)).isEqualTo(1);
    }

    private static DonWorryPaymentResponse donWorry(String status, boolean authorized) {
        return new DonWorryPaymentResponse(101L, "ORD-100", 1, status,
                Instant.parse("2026-10-31T03:00:00Z"), Instant.parse("2026-10-31T03:01:00Z"), 52_000L, null,
                authorized);
    }

    private static Payment local(PaymentStatus status, String paymentKey) {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrderId(7L);
        payment.setProvider(PaymentProvider.OWN);
        payment.setGatewayOrderId("ORD-100");
        payment.setPaymentKey(paymentKey);
        payment.setAmount(BigDecimal.valueOf(52_000));
        payment.setStatus(status);
        return payment;
    }
}
