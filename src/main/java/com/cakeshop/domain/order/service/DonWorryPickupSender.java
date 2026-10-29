package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.dto.DonWorryPickupEvent;
import com.cakeshop.domain.order.infra.DonWorryPickupClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
/**
 * ******************************
 * 작성자 : 민정
 * 담당자 : 주환 (order), 민정 (P6 연동)
 * 작성일 : 2026-10-29
 * 기능 : P6 픽업 커밋 후 송신
 * 설명 : 이미 커밋된 주문을 송신 오류로 실패 처리하지 않는다. 영속 전달 복구는 후속이다.
 * ******************************
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "donworry.pickup.enabled", havingValue = "true")
public class DonWorryPickupSender {
    private final DonWorryPickupQueryService queryService;
    private final DonWorryPickupClient client;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(DonWorryPickupEvent event) {
        try {
            send(event.orderId(), event.completion());
        } catch (RuntimeException exception) {
            // 주문은 이미 커밋됐다. 본문·서명·키·외부 응답을 기록하거나 주문 실패로 되돌리지 않는다.
            log.warn("DonWorry 픽업 전송 실패: orderId={}, completion={}. 동일 업무 요청 재전송 확인이 필요합니다.",
                    event.orderId(), event.completion());
        }
    }

    /** 수동 재확인 시에도 같은 저장 데이터로 전송하고 HTTP마다 새 nonce를 만든다. */
    public void send(long orderId, boolean completion) {
        client.send(queryService.getMessage(orderId, completion), completion);
    }
}
