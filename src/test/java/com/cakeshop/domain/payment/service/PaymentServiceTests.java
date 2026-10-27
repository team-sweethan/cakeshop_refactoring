package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentCommandService;
import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentExecutionOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentProduct;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.ApprovalResult;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.event.CustomPaymentChatCompletedEvent;
import com.cakeshop.domain.payment.event.GeneralPaymentCompletedEvent;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 10, 0);

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private ProductStockService productStockService;

    @Mock
    private OrderPaymentCommandService orderPaymentCommandService;

    @Mock
    private PaymentRecoveryService paymentRecoveryService;

    @Mock
    private CouponOrderCommandService couponOrderCommandService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentMapper,
                productStockService,
                orderPaymentCommandService,
                paymentRecoveryService,
                Clock.fixed(
                        NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        ZoneId.of("Asia/Seoul")
                ),
                couponOrderCommandService,
                eventPublisher
        );
    }

    @Test
    void completePayment_generalOrder_updatesStockPaymentAndOrderInOrder() {
        PaymentExecutionOrder order = order();
        Payment payment = payment();
        ApprovalResult approval = approval();
        when(productStockService.decreaseStock(100L, 2)).thenReturn(true);
        when(paymentMapper.completeIfReady(
                20L,
                "payment-key",
                "카드",
                approval.approvedAt()
        )).thenReturn(1);

        paymentService.completePayment(order, payment, approval);

        InOrder inOrder = inOrder(
                productStockService,
                paymentMapper,
                orderPaymentCommandService,
                couponOrderCommandService,
                paymentRecoveryService
        );
        inOrder.verify(orderPaymentCommandService).lockGeneralOrderForPayment(1L);
        inOrder.verify(productStockService).decreaseStock(100L, 2);
        inOrder.verify(orderPaymentCommandService).recordStockDeduction(
                200L,
                approval.approvedAt()
        );
        inOrder.verify(paymentMapper).completeIfReady(
                20L,
                "payment-key",
                "카드",
                approval.approvedAt()
        );
        inOrder.verify(orderPaymentCommandService).completeGeneralOrderAfterPayment(
                1L,
                approval.approvedAt()
        );
        inOrder.verify(couponOrderCommandService).useReservedCouponForOrder(1L);
        inOrder.verify(paymentRecoveryService).discardApprovalRecovery(payment);
        verify(eventPublisher).publishEvent(new GeneralPaymentCompletedEvent(1L));
    }

    @Test
    void completePayment_customOrder_updatesStockPaymentAndUnderReviewInOrder() {
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                1L,
                OrderType.CUSTOM,
                BigDecimal.valueOf(30_000),
                NOW.plusMinutes(10),
                List.of(new PaymentProduct(200L, 100L, 1))
        );
        Payment payment = payment();
        ApprovalResult approval = approval();
        when(productStockService.decreaseStock(100L, 1)).thenReturn(true);
        when(paymentMapper.completeIfReady(
                20L,
                "payment-key",
                "카드",
                approval.approvedAt()
        )).thenReturn(1);

        paymentService.completePayment(order, payment, approval);

        InOrder inOrder = inOrder(
                productStockService,
                paymentMapper,
                orderPaymentCommandService,
                couponOrderCommandService,
                paymentRecoveryService
        );
        inOrder.verify(orderPaymentCommandService).lockOrderForPayment(1L);
        inOrder.verify(productStockService).decreaseStock(100L, 1);
        inOrder.verify(orderPaymentCommandService).recordStockDeduction(
                200L,
                approval.approvedAt()
        );
        inOrder.verify(paymentMapper).completeIfReady(
                20L,
                "payment-key",
                "카드",
                approval.approvedAt()
        );
        inOrder.verify(orderPaymentCommandService).completeCustomOrderAfterPayment(
                1L,
                approval.approvedAt()
        );
        inOrder.verify(couponOrderCommandService).useReservedCouponForOrder(1L);
        inOrder.verify(paymentRecoveryService).discardApprovalRecovery(payment);
        verify(orderPaymentCommandService, never()).completeGeneralOrderAfterPayment(
                1L,
                approval.approvedAt()
        );
        verify(eventPublisher).publishEvent(new CustomPaymentChatCompletedEvent(1L));
    }

    @Test
    void completePayment_generalOrderPaymentUpdateFails_doesNotUpdateOrder() {
        PaymentExecutionOrder order = order();
        Payment payment = payment();
        ApprovalResult approval = approval();
        when(productStockService.decreaseStock(100L, 2)).thenReturn(true);
        when(paymentMapper.completeIfReady(
                20L,
                "payment-key",
                "카드",
                approval.approvedAt()
        )).thenReturn(0);

        assertThatThrownBy(() -> paymentService.completePayment(
                order,
                payment,
                approval
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_COMPLETE_FAILED)
        );

        verify(productStockService).decreaseStock(100L, 2);
        verify(orderPaymentCommandService).lockGeneralOrderForPayment(1L);
        verify(orderPaymentCommandService).recordStockDeduction(200L, approval.approvedAt());
        verify(orderPaymentCommandService, never()).completeGeneralOrderAfterPayment(
                1L,
                approval.approvedAt()
        );
    }

    @Test
    void completePayment_generalOrderExpiredAfterOrderLock_doesNotChangeInternalState() {
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                1L,
                BigDecimal.valueOf(30_000),
                NOW,
                List.of(new PaymentProduct(200L, 100L, 2))
        );

        assertThatThrownBy(() -> paymentService.completePayment(
                order,
                payment(),
                approval()
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_EXPIRED)
        );

        verify(orderPaymentCommandService).lockGeneralOrderForPayment(1L);
        verifyNoInteractions(
                productStockService,
                paymentMapper,
                couponOrderCommandService,
                paymentRecoveryService
        );
    }

    @Test
    void completePayment_isTransactional() throws NoSuchMethodException {
        Transactional transactional = PaymentService.class
                .getMethod(
                        "completePayment",
                        PaymentExecutionOrder.class,
                        Payment.class,
                        ApprovalResult.class
                )
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
    }

    @Test
    void completeZeroAmountGeneralPayment_zeroAmount_completesWithoutPgApproval() {
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                1L, BigDecimal.ZERO, LocalDateTime.of(2026, 8, 1, 10, 10),
                List.of(new PaymentProduct(200L, 100L, 2))
        );
        Payment payment = payment();
        payment.setAmount(BigDecimal.ZERO);
        LocalDateTime completedAt = LocalDateTime.of(2026, 8, 1, 10, 1);
        when(productStockService.decreaseStock(100L, 2)).thenReturn(true);
        when(paymentMapper.completeZeroAmountIfReady(20L, completedAt)).thenReturn(1);

        paymentService.completeZeroAmountGeneralPayment(order, payment, completedAt);

        verify(orderPaymentCommandService).lockGeneralOrderForPayment(1L);
        verify(productStockService).decreaseStock(100L, 2);
        verify(orderPaymentCommandService).recordStockDeduction(200L, completedAt);
        verify(paymentMapper).completeZeroAmountIfReady(20L, completedAt);
        verify(orderPaymentCommandService).completeGeneralOrderAfterPayment(1L, completedAt);
        verify(couponOrderCommandService).useReservedCouponForOrder(1L);
        verify(eventPublisher).publishEvent(new GeneralPaymentCompletedEvent(1L));
        verifyNoInteractions(paymentRecoveryService);
    }

    private PaymentExecutionOrder order() {
        return new PaymentExecutionOrder(
                1L,
                BigDecimal.valueOf(30_000),
                LocalDateTime.of(2026, 8, 1, 10, 10),
                List.of(new PaymentProduct(200L, 100L, 2))
        );
    }

    private Payment payment() {
        Payment payment = new Payment();
        payment.setProvider(PaymentProvider.TOSS);
        payment.setId(20L);
        payment.setOrderId(1L);
        return payment;
    }

    private ApprovalResult approval() {
        return new ApprovalResult(
                "payment-key",
                "ORD-100",
                "카드",
                PaymentGatewayStatus.DONE,
                30_000L,
                LocalDateTime.of(2026, 8, 1, 10, 1)
        );
    }
}
