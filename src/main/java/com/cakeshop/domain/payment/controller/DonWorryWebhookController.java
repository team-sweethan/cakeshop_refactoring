package com.cakeshop.domain.payment.controller;

import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.service.DonWorryPaymentSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-02
 * 기능 : 돈워리 결제 상태 웹훅 수신
 * 설명 : 서명이 맞으면 본문의 paymentId로 돈워리에 다시 조회해 반영한다(본문 상태는 믿지 않는다).
 *        2xx를 돌려주면 돈워리는 다시 보내지 않는다. 반영 중 오류는 500으로 돌려 재전송을 받는다.
 * ******************************
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "donworry.payment.enabled", havingValue = "true")
public class DonWorryWebhookController {

    private final DonWorryPaymentClient client;
    private final DonWorryPaymentSyncService syncService;
    private final ObjectMapper json;

    public DonWorryWebhookController(
            DonWorryPaymentClient client,
            DonWorryPaymentSyncService syncService,
            ObjectMapper json
    ) {
        this.client = client;
        this.syncService = syncService;
        this.json = json;
    }

    @PostMapping("/webhooks/donworry")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Merchant-Code", required = false) String merchantCode,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body
    ) {
        if (!client.verifyWebhook(merchantCode, timestamp, eventId, signature, body)) {
            return ResponseEntity.status(401).build();
        }
        long paymentId;
        try {
            JsonNode node = json.readTree(body).get("paymentId");
            if (node == null || !node.canConvertToLong() || node.asLong() <= 0) {
                return ResponseEntity.badRequest().build();
            }
            paymentId = node.asLong();
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().build();
        }
        try {
            var result = syncService.sync(paymentId);
            log.info("donworry webhook eventId={} result={}", eventId, result);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException exception) {
            log.warn("donworry webhook failed eventId={} reason={}", eventId, exception.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }
}
