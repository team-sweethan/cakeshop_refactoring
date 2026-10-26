package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrderItem;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentOrderOption;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.payment.dto.form.TossPaymentSuccessForm;
import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.dto.view.PaymentCompletionView;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.infra.TossPaymentAvailability;
import com.cakeshop.domain.payment.infra.TossPaymentGateway;
import com.cakeshop.domain.store.dto.view.StoreView;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCheckoutServiceTests {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 8, 3, 10, 0);
    private static final Clock CLOCK = Clock.fixed(
            NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private OrderPaymentQueryService orderPaymentQueryService;

    @Mock
    private MemberService memberService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private StoreService storeService;

    @Mock
    private TossPaymentAvailability tossPaymentAvailability;

    private PaymentCheckoutService paymentCheckoutService;

    @BeforeEach
    void setUp() {
        paymentCheckoutService = new PaymentCheckoutService(
                orderPaymentQueryService,
                memberService,
                paymentService,
                storeService,
                CLOCK,
                new TossPaymentGateway(null, tossPaymentAvailability)
        );
        lenient().when(memberService.isActiveMember(10L)).thenReturn(true);
        lenient().when(tossPaymentAvailability.clientKey()).thenReturn("test-client-key");
        lenient().when(tossPaymentAvailability.isEnabled()).thenReturn(true);
    }

    @Test
    void getCheckout_ownedPendingOrder_returnsActualPaymentView() {
        PaymentOrder order = order(true, true);
        Payment payment = payment(PaymentStatus.READY, null);
        when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L)).thenReturn(order);
        when(paymentService.getReadyPayment(1L)).thenReturn(payment);

        PaymentCheckoutView checkout = paymentCheckoutService.getCheckout(
                10L,
                "member@example.com",
                1L
        );

        assertThat(checkout.orderNumber()).isEqualTo("ORD-100");
        assertThat(checkout.tossOrderId()).isEqualTo("ORD-100");
        assertThat(checkout.orderName()).isEqualTo("딸기 생크림 케이크");
        assertThat(checkout.amount()).isEqualByComparingTo("30000");
        assertThat(checkout.customerEmail()).isEqualTo("member@example.com");
        assertThat(checkout.paymentAvailable()).isTrue();
        assertThat(checkout.items()).singleElement().satisfies(item -> {
            assertThat(item.productName()).isEqualTo("딸기 생크림 케이크");
            assertThat(item.options()).singleElement().satisfies(option ->
                    assertThat(option.optionName()).isEqualTo("1호")
            );
        });
    }

    @Test
    void getCheckout_customPendingOrder_returnsPaymentView() {
        when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L))
                .thenReturn(order(false, true));
        when(paymentService.getReadyPayment(1L)).thenReturn(payment(PaymentStatus.READY, null));

        PaymentCheckoutView checkout = paymentCheckoutService.getCheckout(
                10L,
                "member@example.com",
                1L
        );

        assertThat(checkout.orderNumber()).isEqualTo("ORD-100");
        assertThat(checkout.amount()).isEqualByComparingTo("30000");
    }

    @Test
    void getCheckout_productNameOver100Characters_truncatesOrderName() {
        String longProductName = "가".repeat(101);
        when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L))
                .thenReturn(order(true, true, longProductName));
        when(paymentService.getReadyPayment(1L)).thenReturn(payment(PaymentStatus.READY, null));

        PaymentCheckoutView checkout = paymentCheckoutService.getCheckout(
                10L,
                "member@example.com",
                1L
        );

        assertThat(checkout.orderName()).hasSize(100);
    }

    @Test
    void getCheckout_paymentConfigurationDisabled_disablesPayment() {
        when(tossPaymentAvailability.isEnabled()).thenReturn(false);
        when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L)).thenReturn(order(true, true));
        when(paymentService.getReadyPayment(1L)).thenReturn(payment(PaymentStatus.READY, null));

        PaymentCheckoutView checkout = paymentCheckoutService.getCheckout(10L, "member@example.com", 1L);

        assertThat(checkout.paymentAvailable()).isFalse();
    }

    @Test
    void getCheckout_inactiveMember_rejectsBeforeOpeningPayment() {
        when(memberService.isActiveMember(10L)).thenReturn(false);

        assertThatThrownBy(() -> paymentCheckoutService.getCheckout(
                10L,
                "member@example.com",
                1L
        )).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN)
        );
    }

    @Test
    void validateSuccessCallback_completedSamePayment_acceptsCallback() {
        PaymentOrder order = order(true, false);
        Payment payment = payment(PaymentStatus.DONE, "payment-key");
        when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L)).thenReturn(order);
        when(paymentService.findDonePayment(1L)).thenReturn(Optional.of(payment));

        paymentCheckoutService.validateSuccessCallback(
                10L,
                1L,
                successForm()
        );
    }

    @Test
    void getFailure_providerMessage_returnsOnlyMappedSafeMessage() {
        when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L))
                .thenReturn(order(true, true));

        var failure = paymentCheckoutService.getFailure(
                10L,
                1L,
                "PAY_PROCESS_CANCELED"
        );

        assertThat(failure.retryAvailable()).isTrue();
        assertThat(failure.message()).contains("결제가 취소되었습니다");
    }

    @Test
    void getCompletion_donePayment_returnsActualMethodAndPickupPlace() {
        when(orderPaymentQueryService.getMemberPaymentOrder(10L, 1L))
                .thenReturn(order(true, false));
        when(paymentService.getDonePayment(1L))
                .thenReturn(payment(PaymentStatus.DONE, "payment-key"));
        when(storeService.getStoreView()).thenReturn(storeView());

        PaymentCompletionView completion =
                paymentCheckoutService.getCompletion(10L, 1L);

        assertThat(completion.orderNumber()).isEqualTo("ORD-100");
        assertThat(completion.method()).isEqualTo("카드");
        assertThat(completion.pickupPlace()).isEqualTo("1층 픽업 데스크");
        assertThat(completion.items()).singleElement().satisfies(item ->
                assertThat(item.productName()).isEqualTo("딸기 생크림 케이크")
        );
    }

    private PaymentOrder order(boolean generalOrder, boolean pendingPayment) {
        return order(generalOrder, pendingPayment, "딸기 생크림 케이크");
    }

    private PaymentOrder order(boolean generalOrder, boolean pendingPayment, String productName) {
        return new PaymentOrder(
                1L,
                "ORD-100",
                generalOrder,
                pendingPayment,
                BigDecimal.valueOf(30_000),
                BigDecimal.ZERO,
                BigDecimal.valueOf(30_000),
                NOW.plusDays(1),
                NOW.plusMinutes(10),
                "홍길동",
                "010-1111-2222",
                List.of(new PaymentOrderItem(
                        productName,
                        1,
                        BigDecimal.valueOf(30_000),
                        List.of(new PaymentOrderOption(
                                "케이크 크기",
                                "1호"
                        ))
                ))
        );
    }

    private Payment payment(PaymentStatus status, String paymentKey) {
        Payment payment = new Payment();
        payment.setId(20L);
        payment.setOrderId(1L);
        payment.setTossOrderId("ORD-100");
        payment.setPaymentKey(paymentKey);
        payment.setIdempotencyKey("PAY-1");
        payment.setMethod("카드");
        payment.setAmount(BigDecimal.valueOf(30_000));
        payment.setStatus(status);
        return payment;
    }

    private TossPaymentSuccessForm successForm() {
        TossPaymentSuccessForm form = new TossPaymentSuccessForm();
        form.setPaymentKey("payment-key");
        form.setOrderId("ORD-100");
        form.setAmount(BigDecimal.valueOf(30_000));
        return form;
    }

    private StoreView storeView() {
        return new StoreView(
                1L,
                "케이크샵",
                null,
                null,
                "서울시",
                "02-000-0000",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                LocalTime.of(10, 0),
                LocalTime.of(16, 0),
                Set.of(),
                "1층 픽업 데스크",
                LocalTime.of(10, 0),
                LocalTime.of(18, 0),
                30,
                List.of()
        );
    }
}
