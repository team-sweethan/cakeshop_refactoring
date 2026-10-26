package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberOrderQueryService;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.service.payment.OrderPaymentCancellationCommandService;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.service.RefundService.RefundRequest;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 15, 0);
    private static final Clock CLOCK = Clock.fixed(
            NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private OrderPaymentCancellationCommandService orderPaymentCancellationCommandService;

    @Mock
    private MemberService memberService;

    @Mock
    private MemberOrderQueryService memberOrderQueryService;

    private RefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundService(
                paymentMapper,
                orderPaymentCancellationCommandService,
                memberService,
                memberOrderQueryService,
                CLOCK
        );
        lenient().when(memberService.isActiveMember(anyLong())).thenReturn(true);
        lenient().when(memberOrderQueryService.isActiveAdmin(anyLong())).thenReturn(true);
    }

    @Test
    void prepareCustomerCancellation_inactiveMember_returnsMemberNotAvailable() {
        when(memberService.isActiveMember(3L)).thenReturn(false);

        assertThatThrownBy(() -> refundService.prepareCustomerCancellation(3L, 10L, "단순 변심"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(OrderErrorCode.MEMBER_NOT_AVAILABLE)
                );

        verify(orderPaymentCancellationCommandService, never())
                .lockCustomerOrderForPaymentCancellation(anyLong(), anyLong());
    }

    @Test
    void prepareCustomerCancellation_availableGeneralOrder_createsFullCancellationRequest() {
        Payment payment = payment();
        when(paymentMapper.findDonePaymentByOrderId(10L)).thenReturn(Optional.of(payment));
        when(orderPaymentCancellationCommandService.isPaymentCancellationAvailable(
                10L, "CUSTOMER", NOW
        )).thenReturn(true);
        when(paymentMapper.insertPaymentCancellation(any(PaymentCancellation.class)))
                .thenAnswer(invocation -> {
                    invocation.<PaymentCancellation>getArgument(0).setId(30L);
                    return 1;
                });

        RefundRequest result = refundService.prepareCustomerCancellation(3L, 10L, " 단순 변심 ");

        assertThat(result.cancellationId()).isEqualTo(30L);
        assertThat(result.paymentKey()).isEqualTo("payment-key");
        assertThat(result.reason()).isEqualTo("단순 변심");
        assertThat(result.canceledBy()).isEqualTo("CUSTOMER");
        assertThat(result.requestedAt()).isEqualTo(NOW);
        verify(orderPaymentCancellationCommandService).lockCustomerOrderForPaymentCancellation(3L, 10L);
    }

    @Test
    void prepareCustomerCancellation_existingRequest_usesOriginalRequestedAt() {
        PaymentCancellation existing = cancellation();
        existing.setRequestedAt(NOW.minusMinutes(1));
        when(paymentMapper.findDonePaymentByOrderId(10L)).thenReturn(Optional.of(payment()));
        when(paymentMapper.findRequestedCancellationByPaymentId(20L)).thenReturn(Optional.of(existing));
        when(orderPaymentCancellationCommandService.isPaymentCancellationAvailable(
                10L, "CUSTOMER", NOW.minusMinutes(1)
        )).thenReturn(true);

        RefundRequest result = refundService.prepareCustomerCancellation(3L, 10L, "변경된 취소 사유");

        assertThat(result.idempotencyKey()).isEqualTo("idempotency-key");
        assertThat(result.reason()).isEqualTo("단순 변심");
        verify(paymentMapper, never()).insertPaymentCancellation(any());
    }

    @Test
    void prepareAdminRejection_underReviewCustomOrder_createsDedicatedRequestType() {
        when(paymentMapper.findDonePaymentByOrderId(10L)).thenReturn(Optional.of(payment()));
        when(orderPaymentCancellationCommandService.isPaymentCancellationAvailable(
                10L, "ADMIN_REJECTION", NOW
        )).thenReturn(true);
        when(paymentMapper.insertPaymentCancellation(any(PaymentCancellation.class)))
                .thenAnswer(invocation -> {
                    invocation.<PaymentCancellation>getArgument(0).setId(30L);
                    return 1;
                });

        RefundRequest result = refundService.prepareAdminRejection(7L, 10L, "제작 일정이 부족합니다.");

        assertThat(result.canceledBy()).isEqualTo("ADMIN_REJECTION");
        verify(orderPaymentCancellationCommandService).lockOrderForPaymentCancellation(10L);
    }

    @Test
    void prepareAdminRejection_suspendedOrDemotedAdmin_rejectsBeforeOrderLock() {
        when(memberOrderQueryService.isActiveAdmin(7L)).thenReturn(false);

        assertThatThrownBy(() -> refundService.prepareAdminRejection(7L, 10L, "제작 일정이 부족합니다."))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN)
                );

        verify(orderPaymentCancellationCommandService, never()).lockOrderForPaymentCancellation(10L);
        verify(paymentMapper, never()).findDonePaymentByOrderId(anyLong());
    }

    @Test
    void prepareCustomerCancellation_inProductionOrder_rejectsBeforeRequestIsSaved() {
        when(paymentMapper.findDonePaymentByOrderId(10L)).thenReturn(Optional.of(payment()));
        when(orderPaymentCancellationCommandService.isPaymentCancellationAvailable(
                10L, "CUSTOMER", NOW
        )).thenReturn(false);

        assertThatThrownBy(() -> refundService.prepareCustomerCancellation(3L, 10L, "단순 변심"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(PaymentErrorCode.PAYMENT_CANCEL_NOT_AVAILABLE)
                );

        verify(paymentMapper, never()).insertPaymentCancellation(any());
    }

    @Test
    void cancelCustomerZeroAmountOrder_cancelsPaymentThenCompletesOrderCancellation() {
        Payment payment = payment();
        payment.setAmount(BigDecimal.ZERO);
        when(paymentMapper.findDonePaymentByOrderId(10L)).thenReturn(Optional.of(payment));
        when(orderPaymentCancellationCommandService.isPaymentCancellationAvailable(
                10L, "CUSTOMER", NOW
        )).thenReturn(true);
        when(paymentMapper.cancelIfDone(20L, NOW)).thenReturn(1);
        when(orderPaymentCancellationCommandService.completePaymentCancellation(
                10L, "CUSTOMER", 3L, "cancel", NOW, NOW
        )).thenReturn(true);

        assertThat(refundService.cancelCustomerZeroAmountOrder(3L, 10L, "cancel")).isTrue();

        verify(orderPaymentCancellationCommandService).lockCustomerOrderForPaymentCancellation(3L, 10L);
        verify(paymentMapper, never()).insertPaymentCancellation(any());
    }

    @Test
    void completeCancellation_providerSuccess_completesPaymentThenOrder() {
        PaymentCancellation cancellation = cancellation();
        CancellationResult result = new CancellationResult(PaymentGatewayStatus.CANCELED, "transaction-key", NOW.plusSeconds(2));
        when(paymentMapper.findPaymentCancellationByIdForUpdate(30L)).thenReturn(Optional.of(cancellation));
        when(paymentMapper.findPaymentById(20L)).thenReturn(Optional.of(payment()));
        when(paymentMapper.completeCancellationIfRequested(30L, "transaction-key", NOW.plusSeconds(2)))
                .thenReturn(1);
        when(orderPaymentCancellationCommandService.completePaymentCancellation(
                10L, "CUSTOMER", 3L, "단순 변심", NOW, NOW.plusSeconds(2)
        )).thenReturn(true);

        refundService.completeCancellation(request(), result);

        verify(orderPaymentCancellationCommandService).completePaymentCancellation(
                10L, "CUSTOMER", 3L, "단순 변심", NOW, NOW.plusSeconds(2)
        );
    }

    @Test
    void completeCancellation_completedRequest_requiresCanceledOrder() {
        PaymentCancellation cancellation = cancellation();
        cancellation.setStatus(PaymentCancellationStatus.DONE);
        cancellation.setTransactionKey("transaction-key");
        Payment payment = payment();
        payment.setStatus(PaymentStatus.CANCELED);
        when(paymentMapper.findPaymentCancellationByIdForUpdate(30L)).thenReturn(Optional.of(cancellation));
        when(paymentMapper.findPaymentById(20L)).thenReturn(Optional.of(payment));
        when(orderPaymentCancellationCommandService.isPaymentCancellationCompleted(10L, "CUSTOMER"))
                .thenReturn(true);
        when(orderPaymentCancellationCommandService.completePaymentCancellation(
                10L, "CUSTOMER", 3L, "단순 변심", NOW, NOW.plusSeconds(2)
        )).thenReturn(true);

        refundService.completeCancellation(
                request(),
                new CancellationResult(PaymentGatewayStatus.CANCELED, "transaction-key", NOW.plusSeconds(2))
        );

        verify(paymentMapper, never()).completeCancellationIfRequested(anyLong(), any(), any());
        verify(orderPaymentCancellationCommandService).completePaymentCancellation(
                10L, "CUSTOMER", 3L, "단순 변심", NOW, NOW.plusSeconds(2)
        );
    }

    @Test
    void getRequestedCancellations_availableOrder_mapsRecoveryRequest() {
        PaymentCancellation cancellation = cancellation();
        cancellation.setRequestedAt(NOW.minusMinutes(2));
        when(paymentMapper.findRequestedRefundCancellations(10)).thenReturn(List.of(cancellation));
        when(paymentMapper.findPaymentById(20L)).thenReturn(Optional.of(payment()));
        when(orderPaymentCancellationCommandService.isPaymentCancellationAvailable(
                10L, "CUSTOMER", NOW.minusMinutes(2)
        )).thenReturn(true);

        assertThat(refundService.getRequestedCancellations(10))
                .singleElement()
                .satisfies(request -> assertThat(request.cancellationId()).isEqualTo(30L));
    }

    private Payment payment() {
        Payment payment = new Payment();
        payment.setId(20L);
        payment.setOrderId(10L);
        payment.setPaymentKey("payment-key");
        payment.setAmount(BigDecimal.valueOf(40_000));
        return payment;
    }

    private PaymentCancellation cancellation() {
        PaymentCancellation cancellation = new PaymentCancellation();
        cancellation.setId(30L);
        cancellation.setPaymentId(20L);
        cancellation.setIdempotencyKey("idempotency-key");
        cancellation.setCancelReason("단순 변심");
        cancellation.setRequestType("CUSTOMER");
        cancellation.setRequestedBy(3L);
        cancellation.setStatus(PaymentCancellationStatus.REQUESTED);
        cancellation.setRequestedAt(NOW);
        return cancellation;
    }

    private RefundRequest request() {
        return new RefundRequest(
                30L,
                10L,
                "payment-key",
                "idempotency-key",
                "단순 변심",
                "CUSTOMER",
                NOW
        );
    }
}
