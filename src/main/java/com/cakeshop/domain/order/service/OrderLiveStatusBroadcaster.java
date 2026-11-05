package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.dto.OrderLiveStatusEvent;
import com.cakeshop.domain.order.mapper.OrderMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (order)
 * 작성일 : 2026-11-05
 * 기능 : 주문 상태 변경 실시간 알림
 * 설명 : 돈워리 판정(승인 · 거절)으로 주문 상태가 바뀌면 커밋 뒤에 열려 있는 화면에 알린다.
 *        관리자 화면은 /topic/admin/orders(관리자만 구독), 고객 화면은 /topic/orders/{회원 ID}(본인만 구독)를 받는다.
 *        메시지에는 주문 ID만 담는다. 화면이 자기 주소를 다시 불러오므로 권한 검사는 기존 화면 요청이 그대로 한다.
 *        보내기에 실패해도 주문 처리는 이미 커밋됐으므로 되돌리지 않는다(화면은 새로고침하면 맞다).
 * ******************************
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderLiveStatusBroadcaster {

    static final String ADMIN_TOPIC = "/topic/admin/orders";
    static final String MEMBER_TOPIC_PREFIX = "/topic/orders/";

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderMapper orderMapper;

    // 트랜잭션 없이 발행돼도(조회 배치 등) 보낸다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(OrderLiveStatusEvent event) {
        try {
            Map<String, Long> payload = Map.of("orderId", event.orderId());
            messagingTemplate.convertAndSend(ADMIN_TOPIC, payload);
            orderMapper.findOrderById(event.orderId())
                    .map(order -> order.getMemberId())
                    .ifPresent(memberId -> messagingTemplate.convertAndSend(MEMBER_TOPIC_PREFIX + memberId, payload));
        } catch (RuntimeException exception) {
            log.warn("주문 상태 실시간 알림 실패: orderId={}", event.orderId());
        }
    }
}
