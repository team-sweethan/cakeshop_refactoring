package com.cakeshop.domain.order.dto.form.admin;

import com.cakeshop.domain.order.entity.OrderStatus;
import lombok.Getter;
import lombok.Setter;
/** 관리자 주문 처리 목록의 작업 단계 · 정렬 검색 조건이다. */
@Getter
@Setter
public class FulfillmentSearchCondition {
    private OrderStatus status;
    // 단계에 맞지 않거나 비어 있으면 그 단계의 기본 정렬(FulfillmentSort.resolve)
    private FulfillmentSort sort;
}
