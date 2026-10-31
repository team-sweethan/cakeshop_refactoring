package com.cakeshop.domain.payment.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService.DonWorryCheckoutSession;
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

@ExtendWith(MockitoExtension.class)
class DonWorryCheckoutControllerTests {

    @Mock
    private DonWorryCheckoutService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DonWorryCheckoutController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        MemberDetails principal = new MemberDetails(
                new MemberAuthenticationView(10L, "member@example.com", "password", "USER", true));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, principal.getPassword(), principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void prepare_returnsCheckoutUrlAndOriginForModal() throws Exception {
        when(service.prepare(10L, "member@example.com", 1L)).thenReturn(
                new DonWorryCheckoutSession("http://localhost:5174/checkout/pss_1", "http://localhost:5174"));

        mockMvc.perform(post("/orders/1/payment/donworry/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("http://localhost:5174/checkout/pss_1"))
                .andExpect(jsonPath("$.checkoutOrigin").value("http://localhost:5174"));
    }

    @Test
    void returnFromCheckout_redirectsToExistingSuccessCallback() throws Exception {
        when(service.successCallbackUrl(1L, "101", "ORD-100", "25000"))
                .thenReturn("/orders/1/payment/success?paymentKey=DW-101&orderId=ORD-100&amount=25000");

        mockMvc.perform(get("/orders/1/payment/donworry/return")
                        .param("paymentKey", "101").param("orderId", "ORD-100").param("amount", "25000"))
                .andExpect(redirectedUrl("/orders/1/payment/success?paymentKey=DW-101&orderId=ORD-100&amount=25000"));
    }
}
