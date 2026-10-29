package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.dto.DonWorryPickupMessage;
import com.cakeshop.domain.order.mapper.OrderMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 민정
 * 담당자 : 주환 (order), 민정 (P6 연동)
 * 작성일 : 2026-10-29
 * 기능 : P6 픽업 전송 스냅샷 조회
 * 설명 : 커밋된 주문을 새 읽기 트랜잭션에서 조회하고 서울 벽시각을 UTC Instant로 변환한다.
 * ******************************
 */
@Service
@RequiredArgsConstructor
public class DonWorryPickupQueryService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final OrderMapper orderMapper;

    // AFTER_COMMIT 시점의 기존 세션을 재사용하지 않고 커밋된 주문·상품을 새 조회 트랜잭션에서 읽는다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public DonWorryPickupMessage getMessage(long orderId, boolean completion) {
        var order = orderMapper.findOrderById(orderId)
                .orElseThrow(() -> new IllegalStateException("픽업 전송 대상 주문이 없습니다."));
        if (order.getOrderNumber() == null || order.getOrderNumber().isBlank()
                || order.getOrderNumber().length() > 100) {
            throw new IllegalStateException("픽업 전송 주문번호를 확인해 주세요.");
        }
        if (completion) {
            return new DonWorryPickupMessage(order.getOrderNumber(), null, toInstant(order.getPickedUpAt()), List.of());
        }
        var items = orderMapper.findOrderItemsByOrderId(orderId).stream().map(item -> {
            if (item.getProductName() == null || item.getProductName().isBlank()
                    || item.getProductName().length() > 150 || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new IllegalStateException("픽업 전송 상품 스냅샷을 확인해 주세요.");
            }
            return new DonWorryPickupMessage.Item(item.getProductName(), item.getQuantity());
        }).toList();
        if (items.isEmpty()) {
            throw new IllegalStateException("픽업 전송 상품 목록이 없습니다.");
        }
        return new DonWorryPickupMessage(order.getOrderNumber(), toInstant(order.getPickupAt()), null, items);
    }

    private Instant toInstant(LocalDateTime time) {
        if (time == null) {
            throw new IllegalStateException("픽업 전송 시각이 없습니다.");
        }
        // CakeShop의 서울 LocalDateTime을 한 번만 UTC로 바꾸고 P6 DATETIME(6) 정밀도에 맞춘다.
        return time.atZone(SEOUL).toInstant().truncatedTo(ChronoUnit.MICROS);
    }
}
