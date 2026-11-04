package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentProperties;
import com.cakeshop.domain.payment.infra.DonWorryPaymentSessionRequest;
import com.cakeshop.domain.payment.infra.DonWorryPaymentSessionResponse;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService.DonWorryCheckoutSession;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class DonWorryCheckoutServiceTests {

    private static final long MEMBER_ID = 10L;
    private static final long ORDER_ID = 1L;
    private static final String EMAIL = "member@example.com";
    // 서울 2026-10-31 12:00 = UTC 03:00
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final DonWorryPaymentSessionResponse SESSION = new DonWorryPaymentSessionResponse(
            "pss_1", "http://localhost:5174/checkout/pss_1", Instant.parse("2026-10-31T03:08:00Z"));

    private final PaymentCheckoutService checkoutService = mock(PaymentCheckoutService.class);
    private final DonWorryPaymentClient client = mock(DonWorryPaymentClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DonWorryPaymentClient> clientProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DonWorryPaymentProperties> propertiesProvider = mock(ObjectProvider.class);
    private final DonWorryCheckoutService service =
            new DonWorryCheckoutService(checkoutService, clientProvider, propertiesProvider, CLOCK);

    @BeforeEach
    void setUp() {
        DonWorryPaymentProperties properties = new DonWorryPaymentProperties();
        properties.setShopBaseUrl(URI.create("http://localhost:8080/"));
        when(propertiesProvider.getIfAvailable()).thenReturn(properties);
        when(clientProvider.getIfAvailable()).thenReturn(client);
    }

    @Test
    void prepare_sendsOrderAndReturnUrlsWithoutCardData() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(true));
        when(client.createSession(any(), anyString())).thenReturn(SESSION);

        DonWorryCheckoutSession session = service.prepare(MEMBER_ID, EMAIL, ORDER_ID);

        ArgumentCaptor<DonWorryPaymentSessionRequest> captor =
                ArgumentCaptor.forClass(DonWorryPaymentSessionRequest.class);
        verify(client).createSession(captor.capture(), anyString());
        DonWorryPaymentSessionRequest request = captor.getValue();
        assertThat(request.orderRef()).isEqualTo("ORD-100");
        assertThat(request.customerRef()).isEqualTo("10");
        assertThat(request.amount()).isEqualTo(25_000L);
        // 결제 기한(서울 12:08) - 10분 = 서울 11:58 = UTC 02:58
        assertThat(request.orderCreatedAt()).isEqualTo(Instant.parse("2026-10-31T02:58:00Z"));
        assertThat(request.orderName()).isEqualTo("딸기 케이크");
        assertThat(request.buyerEmail()).isEqualTo(EMAIL);
        assertThat(request.successUrl()).isEqualTo("http://localhost:8080/orders/1/payment/donworry/return");
        assertThat(request.failUrl()).isEqualTo("http://localhost:8080/orders/1/payment/fail");
        assertThat(session.checkoutUrl()).isEqualTo("http://localhost:5174/checkout/pss_1");
        assertThat(session.checkoutOrigin()).isEqualTo("http://localhost:5174");
    }

    // 돈워리가 거절할 이메일(@ 두 개 · 공백)은 보내지 않는다 — 보내면 결제 준비가 400으로 막힌다
    @Test
    void prepare_emailDonWorryWouldReject_isOmitted() {
        for (String email : new String[] {"a@b@c.com", "a b@c.com", "no-at-sign"}) {
            org.mockito.Mockito.reset(client);
            when(checkoutService.getCheckout(MEMBER_ID, email, ORDER_ID)).thenReturn(checkout(true));
            when(client.createSession(any(), anyString())).thenReturn(SESSION);

            service.prepare(MEMBER_ID, email, ORDER_ID);

            ArgumentCaptor<DonWorryPaymentSessionRequest> captor =
                    ArgumentCaptor.forClass(DonWorryPaymentSessionRequest.class);
            verify(client).createSession(captor.capture(), anyString());
            assertThat(captor.getValue().buyerEmail()).as(email).isNull();
        }
    }

    @Test
    void prepare_unknownResult_retriesOnceWithSameKey() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(true));
        when(client.createSession(any(), anyString()))
                .thenThrow(new DonWorryPaymentException(0, null))
                .thenReturn(SESSION);

        service.prepare(MEMBER_ID, EMAIL, ORDER_ID);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).createSession(any(), keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void prepare_conflict_isApprovalFailure() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(true));
        when(client.createSession(any(), anyString())).thenThrow(new DonWorryPaymentException(409, "ORDER_EXPIRED"));

        assertThatThrownBy(() -> service.prepare(MEMBER_ID, EMAIL, ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_APPROVAL_FAILED));
    }

    @Test
    void prepare_tossPayment_isRejected() {
        when(checkoutService.getCheckout(MEMBER_ID, EMAIL, ORDER_ID)).thenReturn(checkout(false));

        assertThatThrownBy(() -> service.prepare(MEMBER_ID, EMAIL, ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
    }

    @Test
    void successCallbackUrl_prefixesDonWorryPaymentId() {
        assertThat(service.successCallbackUrl(1L, "101", "ORD-100", "25000"))
                .isEqualTo("/orders/1/payment/success?paymentKey=DW-101&orderId=ORD-100&amount=25000");
        assertThatThrownBy(() -> service.successCallbackUrl(1L, "DW-101", "ORD-100", "25000"))
                .isInstanceOf(BusinessException.class);
    }

    private PaymentCheckoutView checkout(boolean donWorry) {
        return new PaymentCheckoutView(
                ORDER_ID, "ORD-100", "ORD-100", "딸기 케이크",
                BigDecimal.valueOf(25_000), BigDecimal.ZERO, BigDecimal.valueOf(25_000),
                LocalDateTime.parse("2026-11-02T15:00:00"), LocalDateTime.parse("2026-10-31T12:08:00"),
                "회원", EMAIL, "01000000000", "", true, List.of(), donWorry, false, true, "");
    }
}
