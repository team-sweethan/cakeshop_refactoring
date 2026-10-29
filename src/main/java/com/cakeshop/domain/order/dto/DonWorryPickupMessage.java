package com.cakeshop.domain.order.dto;

import java.time.Instant;
import java.util.List;

/** 주문 당시 상품 스냅샷만 전송하며 고객 개인정보·내부 가맹점 ID는 포함하지 않는다. */
/**
 * ******************************
 * 작성자 : 민정
 * 담당자 : 주환 (order), 민정 (P6 연동)
 * 작성일 : 2026-10-29
 * 기능 : P6 픽업 전송 데이터
 * 설명 : 고객 개인정보 없이 주문번호·저장된 시각·상품 스냅샷만 전달한다.
 * ******************************
 */
public record DonWorryPickupMessage(
        String orderNumber, Instant scheduledPickupAt, Instant pickedUpAt, List<Item> items
) {
    public record Item(String productName, int quantity) {
    }

    public record Schedule(Instant scheduledPickupAt, List<Item> items) {
    }

    public record Completion(Instant pickedUpAt) {
    }
}
