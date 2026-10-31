package com.cakeshop.domain.payment.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.service.PaymentGatewaySelectionService;
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
class PaymentGatewaySelectionControllerTests {

    @Mock
    private PaymentGatewaySelectionService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentGatewaySelectionController(service))
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
    void selectDonWorry_redirectsToDonWorryWindow() throws Exception {
        mockMvc.perform(post("/orders/1/payment/gateway/donworry"))
                .andExpect(redirectedUrl("/orders/1/payment/donworry"));
        verify(service).select(10L, "member@example.com", 1L, PaymentProvider.OWN);
    }

    @Test
    void selectToss_returnsNoContentForScript() throws Exception {
        mockMvc.perform(post("/orders/1/payment/gateway/toss"))
                .andExpect(status().isNoContent());
        verify(service).select(10L, "member@example.com", 1L, PaymentProvider.TOSS);
    }
}
