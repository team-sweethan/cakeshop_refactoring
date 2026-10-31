package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrder;
import com.cakeshop.domain.payment.dto.form.DonWorryPayForm;
import com.cakeshop.domain.payment.dto.view.DonWorryCheckoutView;
import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentRequest;
import com.cakeshop.domain.payment.infra.DonWorryPaymentResponse;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService.DonWorryPaymentResult;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class DonWorryCheckoutServiceTests {

    private static final long MEMBER_ID = 10L;
    private static final long ORDER_ID = 1L;
    private static final String EMAIL = "member@example.com";
    private static final String KEY = "6f1c2a4e-1b2c-4d5e-8f90-0123456789ab";
    // 서울 2026-10-31 12:00 = UTC 03:00
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final PaymentCheckoutService checkoutService = mock(PaymentCheckoutService.class);
    private final OrderPaymentQueryService orderQueryService = mock(OrderPaymentQueryService.class);
    private final DonWorryPaymentClient client = mock(DonWorryPaymentClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DonWorryPaymentClient> clientProvider = mock(ObjectProvider.class);
    private final DonWorryCheckoutService service =
            new DonWorryCheckoutService(checkoutService, orderQueryService, clientProvider, CLOCK);

    @Test
    void openWindow_listsFourCardsWithFreshKey() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(true));

        DonWorryCheckoutView first = service.openWindow(MEMBER_ID, EMAIL, ORDER_ID, "B");
        DonWorryCheckoutView second = service.openWindow(MEMBER_ID, EMAIL, ORDER_ID, "unknown");

        assertThat(first.cards()).extracting(DonWorryDemoCard::code).containsExactly("A", "B", "D", "C");
        assertThat(first.selectedCard()).isEqualTo("B");
        assertThat(second.selectedCard()).isEqualTo("A");
        assertThat(first.idempotencyKey()).isNotEqualTo(second.idempotencyKey());
    }

    @Test
    void openWindow_rejectsTossPayment() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(false));

        assertThatThrownBy(() -> service.openWindow(MEMBER_ID, EMAIL, ORDER_ID, "A"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
    }

    @Test
    void pay_sendsServerAmountAndSignalsWithoutCardNumber() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(true));
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.request(any(), eq(KEY))).thenReturn(response("HELD", null));

        DonWorryPaymentResult result = service.pay(MEMBER_ID, EMAIL, ORDER_ID, form("B"), "device-1", "127.0.0.1");

        ArgumentCaptor<DonWorryPaymentRequest> captor = ArgumentCaptor.forClass(DonWorryPaymentRequest.class);
        verify(client).request(captor.capture(), eq(KEY));
        DonWorryPaymentRequest request = captor.getValue();
        assertThat(request.orderRef()).isEqualTo("ORD-100");
        assertThat(request.customerRef()).isEqualTo("10");
        assertThat(request.amount()).isEqualTo(25_000L);
        // 결제 기한(서울 12:08) - 10분 = 서울 11:58 = UTC 02:58
        assertThat(request.orderCreatedAt()).isEqualTo(Instant.parse("2026-10-31T02:58:00Z"));
        assertThat(request.cardToken()).isEqualTo(DonWorryDemoCard.B.token(MEMBER_ID)).doesNotContain("1173");
        assertThat(request.cardDisplayLabel()).isEqualTo("시연 카드 B **** 1173");
        assertThat(request.deviceId()).isEqualTo("device-1");
        assertThat(request.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(request.buyerEmail()).isEqualTo(EMAIL);
        assertThat(request.ipCountryCode()).isNull();
        assertThat(result.paymentKey()).isEqualTo("DW-101");
        assertThat(result.inProgress()).isTrue();
    }

    @Test
    void pay_unknownResult_isRetryableLookupFailure() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(true));
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.request(any(), eq(KEY))).thenThrow(new DonWorryPaymentException(0, null));

        assertThatThrownBy(() -> service.pay(MEMBER_ID, EMAIL, ORDER_ID, form("A"), "d", "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED));
    }

    @Test
    void pay_withoutClient_isUnavailable() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(true));

        assertThatThrownBy(() -> service.pay(MEMBER_ID, EMAIL, ORDER_ID, form("A"), "d", "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
    }

    @Test
    void status_rejectsPaymentOfAnotherOrder() {
        when(orderQueryService.getMemberPaymentOrder(MEMBER_ID, ORDER_ID)).thenReturn(order("ORD-OTHER"));
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.find(101L)).thenReturn(response("CAPTURED", null));

        assertThatThrownBy(() -> service.status(MEMBER_ID, ORDER_ID, "DW-101"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ORDER_ID_MISMATCH));
    }

    @Test
    void status_rejectsInvalidPaymentKeyBeforeCallingDonWorry() {
        when(orderQueryService.getMemberPaymentOrder(MEMBER_ID, ORDER_ID)).thenReturn(order("ORD-100"));

        assertThatThrownBy(() -> service.status(MEMBER_ID, ORDER_ID, "toss-key"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
    }

    @Test
    void result_failureCodeHidesRuleDetails() {
        assertThat(result("DENIED", "FDS_REJECTED").failureCode()).isEqualTo("FDS_REJECTED");
        assertThat(result("DECLINED", "AUTHORIZATION_DECLINED").failureCode()).isEqualTo("AUTHORIZATION_DECLINED");
        assertThat(result("EXPIRED", null).failureCode()).isEqualTo("EXPIRED");
        assertThat(result("CAPTURED", null).captured()).isTrue();
    }

    private DonWorryPaymentResult result(String status, String declineCode) {
        return new DonWorryPaymentResult(ORDER_ID, "ORD-100", BigDecimal.valueOf(25_000), "DW-101", status, declineCode);
    }

    private DonWorryPayForm form(String card) {
        DonWorryPayForm form = new DonWorryPayForm();
        form.setCard(card);
        form.setIdempotencyKey(KEY);
        return form;
    }

    private PaymentCheckoutView checkout(boolean donWorry) {
        return new PaymentCheckoutView(
                ORDER_ID, "ORD-100", "ORD-100", "딸기 케이크",
                BigDecimal.valueOf(25_000), BigDecimal.ZERO, BigDecimal.valueOf(25_000),
                LocalDateTime.parse("2026-11-02T15:00:00"), LocalDateTime.parse("2026-10-31T12:08:00"),
                "회원", EMAIL, "01000000000", "", true, List.of(), donWorry, false, true, "");
    }

    private PaymentOrder order(String orderNumber) {
        return new PaymentOrder(
                ORDER_ID, orderNumber, OrderType.GENERAL, true,
                BigDecimal.valueOf(25_000), BigDecimal.ZERO, BigDecimal.valueOf(25_000),
                LocalDateTime.parse("2026-11-02T15:00:00"), LocalDateTime.parse("2026-10-31T12:08:00"),
                "회원", "01000000000", List.of());
    }

    private DonWorryPaymentResponse response(String status, String declineCode) {
        return new DonWorryPaymentResponse(101L, "ORD-100", 1, status,
                Instant.parse("2026-10-31T03:00:00Z"), Instant.parse("2026-10-31T03:00:00Z"), 25_000L, declineCode);
    }
}
