package com.cakeshop.domain.order.controller.admin;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.domain.order.controller.admin.FulfillmentAdminController;
import com.cakeshop.domain.order.service.admin.FulfillmentService;
import com.cakeshop.domain.order.service.admin.AdminCustomOrderService;
import com.cakeshop.domain.payment.service.RefundFacade;
import com.cakeshop.global.security.MemberDetails;
import com.cakeshop.global.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FulfillmentAdminController.class)
@Import(SecurityConfig.class)
class FulfillmentAdminControllerSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FulfillmentService fulfillmentService;

    @MockitoBean
    private AdminCustomOrderService adminCustomOrderService;

    @MockitoBean
    private RefundFacade refundFacade;

    @Test
    @WithMockUser(roles = "USER")
    void fulfillment_customer_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/fulfillment"))
                .andExpect(status().isForbidden());
    }

    // PG 결제 확인 중 주문을 픽업 준비로 넘기는 동작도 관리자 전용이다
    @Test
    @WithMockUser(roles = "USER")
    void moveToReadyForPickup_customer_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/fulfillment/10/ready").with(csrf()).param("reviewAcknowledged", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void markPickedUp_missingCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/fulfillment/10/pickup"))
                .andExpect(status().isForbidden());
    }

    @Test
    void markPickedUp_adminWithCsrf_recordsAuthenticatedAdmin() throws Exception {
        MemberDetails admin = new MemberDetails(new MemberAuthenticationView(
                7L,
                "admin@cakeshop.local",
                "dummy",
                "ADMIN",
                true
        ));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                admin,
                null,
                admin.getAuthorities()
        );

        mockMvc.perform(post("/admin/fulfillment/10/pickup")
                        .with(authentication(auth))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/fulfillment"));

        verify(fulfillmentService).markPickedUp(10L, 7L, false);
    }
}
