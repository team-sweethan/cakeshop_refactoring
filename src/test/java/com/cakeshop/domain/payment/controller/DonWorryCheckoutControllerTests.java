package com.cakeshop.domain.payment.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService.DonWorryPaymentResult;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.security.MemberDetails;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import org.hamcrest.Matchers;
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

    private static final String KEY = "6f1c2a4e-1b2c-4d5e-8f90-0123456789ab";
    private static final String DEVICE = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

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
    void pay_captured_redirectsToExistingSuccessCallback() throws Exception {
        when(service.pay(eq(10L), eq("member@example.com"), eq(1L), any(), eq(DEVICE), anyString()))
                .thenReturn(result("CAPTURED", null));

        mockMvc.perform(pay().cookie(new Cookie(DonWorryCheckoutController.DEVICE_COOKIE, DEVICE)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1/payment/success?paymentKey=DW-101&orderId=ORD-100&amount=25000"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void pay_held_redirectsToStatusPageAndIssuesDeviceCookie() throws Exception {
        when(service.pay(eq(10L), anyString(), eq(1L), any(), anyString(), anyString()))
                .thenReturn(result("HELD", null));

        mockMvc.perform(pay())
                .andExpect(redirectedUrl("/orders/1/payment/donworry/DW-101"))
                .andExpect(header().string("Set-Cookie", Matchers.allOf(
                        Matchers.startsWith(DonWorryCheckoutController.DEVICE_COOKIE + "="),
                        Matchers.containsString("HttpOnly"))));
    }

    @Test
    void pay_denied_redirectsToFailWithSafeCode() throws Exception {
        when(service.pay(anyLong(), anyString(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(result("DENIED", "FDS_REJECTED"));

        mockMvc.perform(pay())
                .andExpect(redirectedUrl("/orders/1/payment/fail?code=FDS_REJECTED"));
    }

    @Test
    void pay_invalidCard_isRejectedBeforeService() throws Exception {
        assertThatThrownBy(() -> mockMvc.perform(
                        post("/orders/1/payment/donworry").param("card", "a1").param("idempotencyKey", KEY)))
                .hasCauseInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }

    @Test
    void status_inProgress_rendersWaitingPage() throws Exception {
        when(service.status(10L, 1L, "DW-101")).thenReturn(result("PENDING", null));

        mockMvc.perform(get("/orders/1/payment/donworry/DW-101"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/payment/donworry-status"))
                .andExpect(model().attributeExists("status"));
    }

    @Test
    void status_captured_redirectsToSuccess() throws Exception {
        when(service.status(10L, 1L, "DW-101")).thenReturn(result("CAPTURED", null));

        mockMvc.perform(get("/orders/1/payment/donworry/DW-101"))
                .andExpect(redirectedUrl("/orders/1/payment/success?paymentKey=DW-101&orderId=ORD-100&amount=25000"));
    }

    @Test
    void window_rendersCheckout() throws Exception {
        mockMvc.perform(get("/orders/1/payment/donworry").param("card", "B"))
                .andExpect(view().name("customer/payment/donworry"));
        verify(service).openWindow(10L, "member@example.com", 1L, "B");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pay() {
        return post("/orders/1/payment/donworry").param("card", "B").param("idempotencyKey", KEY);
    }

    private DonWorryPaymentResult result(String status, String declineCode) {
        return new DonWorryPaymentResult(1L, "ORD-100", BigDecimal.valueOf(25_000), "DW-101", status, declineCode);
    }
}
