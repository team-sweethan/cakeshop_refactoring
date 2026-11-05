package com.cakeshop.domain.order.dto.form.admin;

import com.cakeshop.domain.order.entity.OrderStatus;
import java.util.List;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환
 * 작성일 : 2026-11-05
 * 기능 : 관리자 주문 처리 목록 정렬
 * 설명 : 처리할 주문(검토 · 제작 · 픽업 대기)은 픽업 임박순이 기본이고 최근 주문순을 고를 수 있다.
 *        픽업 완료는 최근 완료순이 기본이고 최근 주문순을 고를 수 있다.
 *        단계에 맞지 않는 값은 그 단계의 기본 정렬로 바꾼다.
 * ******************************
 */
public enum FulfillmentSort {
    PICKUP_SOON("픽업 임박순"),
    RECENT_ORDER("최근 주문순"),
    RECENT_PICKED_UP("최근 완료순");

    private final String label;

    FulfillmentSort(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 작업 단계에서 고를 수 있는 정렬. 첫 값이 기본이다. */
    public static List<FulfillmentSort> options(OrderStatus status) {
        return status == OrderStatus.PICKED_UP
                ? List.of(RECENT_PICKED_UP, RECENT_ORDER)
                : List.of(PICKUP_SOON, RECENT_ORDER);
    }

    /** 요청한 정렬이 그 단계에서 고를 수 있는 값이면 그대로, 아니면 그 단계의 기본 정렬을 쓴다. */
    public static FulfillmentSort resolve(FulfillmentSort requested, OrderStatus status) {
        List<FulfillmentSort> options = options(status);
        return requested != null && options.contains(requested) ? requested : options.get(0);
    }
}
