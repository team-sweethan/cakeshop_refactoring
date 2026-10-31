package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentResponse;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class PaymentGatewaySelectionServiceTests {

    private static final long MEMBER_ID = 10L;
    private static final long ORDER_ID = 1L;
    private static final String EMAIL = "member@example.com";

    private final PaymentCheckoutService checkoutService = mock(PaymentCheckoutService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final PaymentGatewayRouter router = mock(PaymentGatewayRouter.class);
    private final DonWorryPaymentClient client = mock(DonWorryPaymentClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DonWorryPaymentClient> clientProvider = mock(ObjectProvider.class);
    private final PaymentGatewaySelectionService service =
            new PaymentGatewaySelectionService(checkoutService, paymentService, router, clientProvider);

    @BeforeEach
    void setUp() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout());
        when(router.isAvailable(any())).thenReturn(true);
        when(clientProvider.getIfAvailable()).thenReturn(client);
    }

    @Test
    void select_tossToDonWorry_changesProvider() {
        Payment payment = payment(PaymentProvider.TOSS);
        when(paymentService.getReadyPayment(ORDER_ID)).thenReturn(payment);

        service.select(MEMBER_ID, EMAIL, ORDER_ID, PaymentProvider.OWN);

        verify(paymentService).changeReadyPaymentProvider(payment, PaymentProvider.OWN);
    }

    @Test
    void select_sameProvider_doesNothing() {
        Payment payment = payment(PaymentProvider.OWN);
        when(paymentService.getReadyPayment(ORDER_ID)).thenReturn(payment);

        service.select(MEMBER_ID, EMAIL, ORDER_ID, PaymentProvider.OWN);

        verify(paymentService, never()).changeReadyPaymentProvider(any(), any());
    }

    @Test
    void select_unavailableProvider_isRejected() {
        when(router.isAvailable(PaymentProvider.TOSS)).thenReturn(false);

        assertThatThrownBy(() -> service.select(MEMBER_ID, EMAIL, ORDER_ID, PaymentProvider.TOSS))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
        verify(paymentService, never()).changeReadyPaymentProvider(any(), any());
    }

    @Test
    void select_donWorryToToss_withoutAttempt_changesProvider() {
        Payment payment = payment(PaymentProvider.OWN);
        when(paymentService.getReadyPayment(ORDER_ID)).thenReturn(payment);
        when(client.findLatestAttempt("ORD-100")).thenReturn(Optional.empty());

        service.select(MEMBER_ID, EMAIL, ORDER_ID, PaymentProvider.TOSS);

        verify(paymentService).changeReadyPaymentProvider(payment, PaymentProvider.TOSS);
    }

    @Test
    void select_donWorryToToss_afterDeclinedAttempt_changesProvider() {
        Payment payment = payment(PaymentProvider.OWN);
        when(paymentService.getReadyPayment(ORDER_ID)).thenReturn(payment);
        when(client.findLatestAttempt("ORD-100")).thenReturn(Optional.of(attempt("DENIED")));

        service.select(MEMBER_ID, EMAIL, ORDER_ID, PaymentProvider.TOSS);

        verify(paymentService).changeReadyPaymentProvider(payment, PaymentProvider.TOSS);
    }

    @Test
    void select_donWorryToToss_whileHeld_isRejectedToPreventDoubleCharge() {
        Payment payment = payment(PaymentProvider.OWN);
        when(paymentService.getReadyPayment(ORDER_ID)).thenReturn(payment);
        when(client.findLatestAttempt("ORD-100")).thenReturn(Optional.of(attempt("HELD")));

        assertThatThrownBy(() -> service.select(MEMBER_ID, EMAIL, ORDER_ID, PaymentProvider.TOSS))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_CHANGE_NOT_ALLOWED));
        verify(paymentService, never()).changeReadyPaymentProvider(any(), any());
    }

    @Test
    void select_donWorryToToss_lookupFailure_isRejected() {
        Payment payment = payment(PaymentProvider.OWN);
        when(paymentService.getReadyPayment(ORDER_ID)).thenReturn(payment);
        when(client.findLatestAttempt("ORD-100")).thenThrow(new DonWorryPaymentException(0, null));

        assertThatThrownBy(() -> service.select(MEMBER_ID, EMAIL, ORDER_ID, PaymentProvider.TOSS))
                .isInstanceOf(BusinessException.class);
        verify(paymentService, never()).changeReadyPaymentProvider(any(), any());
    }

    private Payment payment(PaymentProvider provider) {
        Payment payment = mock(Payment.class);
        when(payment.getProvider()).thenReturn(provider);
        return payment;
    }

    private DonWorryPaymentResponse attempt(String status) {
        return new DonWorryPaymentResponse(3L, "ORD-100", 1, status,
                Instant.parse("2026-10-31T03:00:00Z"), Instant.parse("2026-10-31T03:00:00Z"), 25_000L, null);
    }

    private PaymentCheckoutView checkout() {
        return new PaymentCheckoutView(
                ORDER_ID, "ORD-100", "ORD-100", "딸기 케이크",
                BigDecimal.valueOf(25_000), BigDecimal.ZERO, BigDecimal.valueOf(25_000),
                LocalDateTime.parse("2026-11-02T15:00:00"), LocalDateTime.parse("2026-10-31T12:08:00"),
                "회원", EMAIL, "01000000000", "", true, List.of(), false, true, true, "");
    }
}
