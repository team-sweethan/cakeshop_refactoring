package com.cakeshop.domain.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.service.DonWorryPaymentSyncService;
import com.cakeshop.domain.payment.service.DonWorryPaymentSyncService.SyncResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class DonWorryWebhookControllerTests {

    private static final byte[] BODY = "{\"paymentId\":4,\"paymentStatus\":\"VOIDED\"}".getBytes(StandardCharsets.UTF_8);

    private final DonWorryPaymentClient client = mock(DonWorryPaymentClient.class);
    private final DonWorryPaymentSyncService syncService = mock(DonWorryPaymentSyncService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DonWorryWebhookController(client, syncService, JsonMapper.builder().build()))
            .build();

    @Test
    void validSignature_syncsByPaymentIdAndAcknowledges() throws Exception {
        when(client.verifyWebhook("CAKESHOP", "1793415600", "evt_4_VOIDED_A", "sig", BODY)).thenReturn(true);
        when(syncService.sync(4L)).thenReturn(SyncResult.VOIDED);

        mockMvc.perform(webhook()).andExpect(status().isNoContent());

        verify(syncService).sync(4L);
    }

    @Test
    void invalidSignature_isUnauthorizedWithoutSync() throws Exception {
        when(client.verifyWebhook(any(), any(), any(), any(), any())).thenReturn(false);

        mockMvc.perform(webhook()).andExpect(status().isUnauthorized());

        verify(syncService, never()).sync(anyLong());
    }

    @Test
    void syncFailure_returnsServerErrorSoDonWorryRetries() throws Exception {
        when(client.verifyWebhook("CAKESHOP", "1793415600", "evt_4_VOIDED_A", "sig", BODY)).thenReturn(true);
        when(syncService.sync(4L)).thenThrow(new IllegalStateException("db"));

        mockMvc.perform(webhook()).andExpect(status().isInternalServerError());
    }

    private static MockHttpServletRequestBuilder webhook() {
        return post("/webhooks/donworry")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Code", "CAKESHOP")
                .header("X-Timestamp", "1793415600")
                .header("X-Event-Id", "evt_4_VOIDED_A")
                .header("X-Signature", "sig")
                .content(BODY);
    }
}
