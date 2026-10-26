package com.cakeshop.domain.payment.controller;

import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.domain.payment.service.PaymentFacade;
import com.cakeshop.domain.payment.service.PaymentCheckoutService;
import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.dto.view.PaymentCompletionView;
import com.cakeshop.domain.payment.dto.view.PaymentFailureView;
import com.cakeshop.global.security.MemberDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTests {

    @Mock
    private PaymentFacade paymentFacade;

    @Mock
    private PaymentCheckoutService paymentQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PaymentController(
                                paymentFacade,
                                paymentQueryService
                        )
                )
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .build();

        MemberDetails principal = new MemberDetails(
                new MemberAuthenticationView(
                        10L,
                        "member@example.com",
                        "password",
                        "USER",
                        true
                )
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        principal.getPassword(),
                        principal.getAuthorities()
                )
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirm_validRequest_usesAuthenticatedMemberAndRedirects() throws Exception {
        mockMvc.perform(post("/orders/1/payment/confirm")
                        .param("paymentKey", "payment-key")
                        .param("gatewayOrderId", "ORD-100")
                        .param("amount", "30000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/orders/complete?orderId=1"
                ));

        verify(paymentFacade).confirmPayment(
                eq(10L),
                eq(1L),
                argThat(form ->
                        form.getPaymentKey().equals("payment-key")
                                && form.getGatewayOrderId().equals("ORD-100")
                                && form.getAmount().compareTo(
                                        BigDecimal.valueOf(30_000)
                                ) == 0
                )
        );
    }

    @Test
    void completeZeroAmountPayment_usesAuthenticatedMemberAndRedirects() throws Exception {
        mockMvc.perform(post("/orders/1/payment/zero"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/complete?orderId=1"));

        verify(paymentFacade).completeZeroAmountGeneralPayment(10L, 1L);
    }

    @Test
    void payment_ownedOrder_addsActualCheckoutModel() throws Exception {
        PaymentCheckoutView checkout = mock(PaymentCheckoutView.class);
        when(paymentQueryService.getCheckout(
                10L,
                "member@example.com",
                1L
        )).thenReturn(checkout);

        mockMvc.perform(get("/orders/1/payment"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/payment/form"))
                .andExpect(model().attribute("payment", checkout));
    }

    @Test
    void success_validCallback_rendersCsrfPostBridge() throws Exception {
        mockMvc.perform(get("/orders/1/payment/success")
                        .param("paymentKey", "payment-key")
                        .param("orderId", "ORD-100")
                        .param("amount", "30000"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/payment/success"))
                .andExpect(model().attributeExists("paymentForm"));
    }

    @Test
    void success_alreadyCompleted_stillRendersConfirmBridgeForProviderReconciliation()
            throws Exception {
        mockMvc.perform(get("/orders/1/payment/success")
                        .param("paymentKey", "payment-key")
                        .param("orderId", "ORD-100")
                        .param("amount", "30000"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/payment/success"))
                .andExpect(model().attributeExists("paymentForm"));
    }

    @Test
    void fail_providerFailure_rendersSafeFailureModel() throws Exception {
        PaymentFailureView failure = new PaymentFailureView(
                1L,
                "안전한 안내",
                true
        );
        when(paymentQueryService.getFailure(10L, 1L, "PAY_PROCESS_CANCELED"))
                .thenReturn(failure);

        mockMvc.perform(get("/orders/1/payment/fail")
                        .param("code", "PAY_PROCESS_CANCELED")
                        .param("message", "provider raw message"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/payment/fail"))
                .andExpect(model().attribute("failure", failure));
    }

    @Test
    void complete_donePayment_addsCompletionModel() throws Exception {
        PaymentCompletionView completion = new PaymentCompletionView(
                1L,
                "ORD-100",
                BigDecimal.valueOf(30_000),
                "카드",
                null,
                "픽업 데스크",
                List.of()
        );
        when(paymentQueryService.getCompletion(10L, 1L))
                .thenReturn(completion);

        mockMvc.perform(get("/orders/complete").param("orderId", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/order/complete"))
                .andExpect(model().attribute("completion", completion));
    }
}
