package com.cakeshop.domain.order.dto.view.admin;

import com.cakeshop.domain.order.entity.OrderStatus;

/** 관리자 주문 처리 탭에 보이는 작업 단계별 주문 수다. */
public record FulfillmentStatusCount(
        OrderStatus status,
        long count
) {
}
