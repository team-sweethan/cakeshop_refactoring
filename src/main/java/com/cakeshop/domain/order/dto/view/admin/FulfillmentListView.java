package com.cakeshop.domain.order.dto.view.admin;

import com.cakeshop.domain.order.dto.form.admin.FulfillmentSort;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.global.common.paging.PageResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 관리자 제작·픽업 목록과 적용된 검색 조건이다.
 * selectedStatus가 null이면 "처리할 주문"(검토 · 제작 · 픽업 대기)이다. 픽업 완료는 PICKED_UP 단계에서만 본다.
 */
public record FulfillmentListView(
        OrderStatus selectedStatus,
        PageResult<FulfillmentOrder> orders,
        FulfillmentSort selectedSort,
        // 탭에 보이는 단계별 주문 수(없는 단계는 0)
        Map<OrderStatus, Long> statusCounts
) {

    /** 정렬 · 단계별 건수 없이 만드는 호출부(테스트 등)는 그 단계의 기본 정렬과 빈 건수를 쓴다. */
    public FulfillmentListView(OrderStatus selectedStatus, PageResult<FulfillmentOrder> orders) {
        this(selectedStatus, orders, FulfillmentSort.resolve(null, selectedStatus), Map.of());
    }

    /** 화면의 정렬 선택지. 첫 값이 그 단계의 기본 정렬이다. */
    public List<FulfillmentSort> sortOptions() {
        return FulfillmentSort.options(selectedStatus);
    }

    /** 단계 하나의 주문 수 */
    public long countOf(OrderStatus status) {
        return statusCounts == null ? 0 : statusCounts.getOrDefault(status, 0L);
    }

    /** "처리할 주문" 탭 건수: 검토 대기 + 제작 중 + 픽업 대기 */
    public long activeCount() {
        return countOf(OrderStatus.UNDER_REVIEW)
                + countOf(OrderStatus.IN_PRODUCTION)
                + countOf(OrderStatus.READY_FOR_PICKUP);
    }

    /** 판정 전 진행 확인 창 문구 (DonWorry #492 합의, ADR-0064 4-1) */
    public static final String PAYMENT_UNDER_REVIEW_WARNING =
            "돈워리가 확인 중인 결제입니다. 진행 후 거절되면 손실은 가맹점이 부담합니다.";

    public record FulfillmentOrder(
            long orderId,
            String orderNumber,
            OrderType orderType,
            OrderStatus status,
            String pickupName,
            String pickupPhone,
            LocalDateTime pickupAt,
            String requestMessage,
            boolean productionStartable,
            boolean productionCompletable,
            boolean rejectionAvailable,
            boolean pickupCompletable,
            // PG 결제 확인 중인 일반 주문을 관리자가 픽업 준비로 넘길 수 있는지
            boolean readyForPickupMovable,
            // PG(돈워리)가 결제를 확인 중인지(판정 전). 진행 버튼은 확인 창을 거친다
            boolean paymentUnderReview,
            List<Item> items
    ) {

        public String orderTypeLabel() {
            return orderType.orderTypeLabel();
        }

        public String statusLabel() {
            return status.statusLabel();
        }

        /**
         * 진행 버튼 확인 창 문구. PG(돈워리)가 결제를 확인 중이면 손실 부담 안내를 앞에 붙인다 (#492 합의 문구).
         */
        public String confirmMessage(String actionMessage) {
            if (!paymentUnderReview) {
                return actionMessage;
            }
            return PAYMENT_UNDER_REVIEW_WARNING + "\n" + actionMessage;
        }

        public String statusClass() {
            return switch (status) {
                case UNDER_REVIEW -> "badge--warning";
                case IN_PRODUCTION -> "badge--info";
                case READY_FOR_PICKUP -> "badge--success";
                case PICKED_UP -> "badge--info";
                default -> "";
            };
        }

    }

    public record Item(
            String productName,
            int quantity,
            String requirements,
            List<String> options
    ) {

        public String optionSummary() {
            return options.isEmpty() ? "기본" : String.join(" · ", options);
        }
    }
}
