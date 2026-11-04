package com.cakeshop.domain.order.service.payment;

import com.cakeshop.domain.order.dto.view.OrderDetailView;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.customer.OrderCustomerService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 주환
 * 담당자 : 주환
 * 작성일 : 2026-08-11
 * 기능 : 결제 연동용 고객 주문 조회 계약
 * 설명 : 결제 도메인이 orders 테이블을 직접 조회하지 않고 고객 소유 결제 대상 주문의 최소 정보를 조회하도록 제공한다.
 * ******************************
 */
@Service
@RequiredArgsConstructor
public class OrderPaymentQueryService {

    private final OrderCustomerService orderCustomerService;
    private final MemberService memberService;
    private final OrderMapper orderMapper;

    /**
     * PG가 결제 완료 뒤 승인을 취소했을 때(돈워리 보류 뒤 보안 직원 거절) 이 주문을 어떻게 할지 (DonWorry ADR-0064 4-1)
     * - 이행 전(승인 대기 · 일반 상품 픽업 준비 · 결제 대기 · 만료 보상): 주문 취소 — 재고 · 쿠폰을 되돌린다
     * - 이행 시작(제작 중 · 수제 케이크 제작 완료 픽업 준비 · 픽업 완료): 주문 유지 — 손실은 PG(돈워리)가 부담한다
     * - 이미 끝난 주문(취소 · 반려): 할 일 없음
     */
    public enum GatewayVoidHandling {
        CANCEL_ORDER,
        KEEP_FULFILLED,
        NOTHING
    }

    @Transactional(readOnly = true)
    public Optional<GatewayVoidHandling> gatewayVoidHandling(long orderId) {
        return orderMapper.findOrderById(orderId).map(order -> gatewayVoidHandling(order.getStatus(), order.getOrderType()));
    }

    static GatewayVoidHandling gatewayVoidHandling(OrderStatus status, OrderType type) {
        return switch (status) {
            case PENDING_PAYMENT, EXPIRED, UNDER_REVIEW -> GatewayVoidHandling.CANCEL_ORDER;
            // 일반 상품은 이미 만들어 둔 재고라 픽업 전이면 되돌릴 수 있다. 수제 케이크의 픽업 준비는 제작이 끝난 뒤다
            case READY_FOR_PICKUP -> type == OrderType.CUSTOM
                    ? GatewayVoidHandling.KEEP_FULFILLED
                    : GatewayVoidHandling.CANCEL_ORDER;
            case IN_PRODUCTION, PICKED_UP -> GatewayVoidHandling.KEEP_FULFILLED;
            case CANCELED, REJECTED -> GatewayVoidHandling.NOTHING;
        };
    }

    /** PG 웹훅·조회 배치처럼 회원 요청이 아닌 결제 반영에서 주문 회원을 찾는다. */
    @Transactional(readOnly = true)
    public Optional<Long> findOrderMemberId(long orderId) {
        return orderMapper.findOrderById(orderId).map(order -> order.getMemberId());
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 고객 소유 결제 대상 주문 조회
     * 설명 : 결제 준비·승인·완료 화면에서 필요한 결제 대상 주문 정보만 제공하고, 주문 Entity를 외부에 노출하지 않는다.
     * ******************************
     */
    @Transactional(readOnly = true)
    public PaymentOrder getMemberPaymentOrder(long memberId, long orderId) {
        OrderDetailView order = orderCustomerService.getMemberOrder(memberId, orderId);
        return new PaymentOrder(
                order.orderId(),
                order.orderNumber(),
                order.orderType(),
                order.status() == OrderStatus.PENDING_PAYMENT,
                order.originalAmount(),
                order.discountAmount(),
                order.finalAmount(),
                order.pickupAt(),
                order.paymentExpiresAt(),
                order.ordererName(),
                order.ordererPhone(),
                order.pickupName(),
                order.pickupPhone(),
                order.items().stream()
                        .map(item -> new PaymentOrderItem(
                                item.productName(),
                                item.quantity(),
                                item.totalAmount(),
                                item.options().stream()
                                        .map(option -> new PaymentOrderOption(
                                                option.groupName(),
                                                option.optionName()
                                        ))
                                        .toList()
                        ))
                        .toList()
        );
    }

    /**
     * ******************************
     * 작성자 : 주환
     * 담당자 : 주환
     * 작성일 : 2026-08-11
     * 기능 : 고객 소유 결제 실행용 주문 조회
     * 설명 : Toss 승인 완료에 필요한 주문 유형·금액·만료 시각·재고 차감 대상만 제공한다.
     * ******************************
     */
    @Transactional(readOnly = true)
    public PaymentExecutionOrder getMemberPaymentExecutionOrder(long memberId, long orderId) {
        if (!memberService.isActiveMember(memberId)) {
            throw new BusinessException(OrderErrorCode.MEMBER_NOT_AVAILABLE);
        }
        Order order = orderMapper.findOrderById(orderId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!Long.valueOf(memberId).equals(order.getMemberId())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                || (order.getOrderType() != OrderType.GENERAL
                && order.getOrderType() != OrderType.CUSTOM)) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
        List<PaymentProduct> products = orderMapper.findOrderItemsByOrderId(orderId).stream()
                .map(item -> toPaymentProduct(item, order.getOrderType()))
                .toList();
        if (products.isEmpty()) {
            throw new BusinessException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }
        return new PaymentExecutionOrder(
                order.getId(),
                order.getOrderType(),
                order.getFinalAmount(),
                order.getPaymentExpiresAt(),
                products
        );
    }

    /** 0원 결제는 일반 주문에만 허용한다. */
    @Transactional(readOnly = true)
    public PaymentExecutionOrder getMemberGeneralPaymentOrder(long memberId, long orderId) {
        PaymentExecutionOrder order = getMemberPaymentExecutionOrder(memberId, orderId);
        if (order.orderType() != OrderType.GENERAL) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION);
        }
        return order;
    }

    private PaymentProduct toPaymentProduct(OrderItem orderItem, OrderType orderType) {
        ProductType expectedProductType = orderType == OrderType.GENERAL
                ? ProductType.GENERAL
                : ProductType.CUSTOM;
        if (orderItem.getProductType() != expectedProductType
                || orderItem.getProductId() == null
                || orderItem.getQuantity() == null
                || orderItem.getQuantity() <= 0) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
        return new PaymentProduct(
                orderItem.getId(),
                orderItem.getProductId(),
                orderItem.getQuantity()
        );
    }

    /**
     * 결제 대상 주문 정보.
     *
     * <p>2026-11-04 (수민): 돈워리 결제 준비 요청에 픽업자를 함께 보내려고 pickupName·pickupPhone을 추가했다.
     * 기존 생성자는 픽업자 없이(null) 만든다.</p>
     */
    public record PaymentOrder(
            long orderId,
            String orderNumber,
            OrderType orderType,
            boolean pendingPayment,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal finalAmount,
            LocalDateTime pickupAt,
            LocalDateTime paymentExpiresAt,
            String ordererName,
            String ordererPhone,
            String pickupName,
            String pickupPhone,
            List<PaymentOrderItem> items
    ) {

        /** 픽업자 정보가 필요 없는 호출부의 호환용 생성자다. */
        public PaymentOrder(
                long orderId,
                String orderNumber,
                OrderType orderType,
                boolean pendingPayment,
                BigDecimal originalAmount,
                BigDecimal discountAmount,
                BigDecimal finalAmount,
                LocalDateTime pickupAt,
                LocalDateTime paymentExpiresAt,
                String ordererName,
                String ordererPhone,
                List<PaymentOrderItem> items
        ) {
            this(
                    orderId,
                    orderNumber,
                    orderType,
                    pendingPayment,
                    originalAmount,
                    discountAmount,
                    finalAmount,
                    pickupAt,
                    paymentExpiresAt,
                    ordererName,
                    ordererPhone,
                    null,
                    null,
                    items
            );
        }

        /** 기존 일반 주문 조회 호출부의 호환용 상태다. */
        public boolean generalOrder() {
            return orderType == OrderType.GENERAL;
        }

        /** 기존 일반 주문 결제 테스트와 호출부의 호환용 생성자다. */
        public PaymentOrder(
                long orderId,
                String orderNumber,
                boolean generalOrder,
                boolean pendingPayment,
                BigDecimal originalAmount,
                BigDecimal discountAmount,
                BigDecimal finalAmount,
                LocalDateTime pickupAt,
                LocalDateTime paymentExpiresAt,
                String ordererName,
                String ordererPhone,
                List<PaymentOrderItem> items
        ) {
            this(
                    orderId,
                    orderNumber,
                    generalOrder ? OrderType.GENERAL : OrderType.CUSTOM,
                    pendingPayment,
                    originalAmount,
                    discountAmount,
                    finalAmount,
                    pickupAt,
                    paymentExpiresAt,
                    ordererName,
                    ordererPhone,
                    items
            );
        }
    }

    public record PaymentOrderItem(
            String productName,
            int quantity,
            BigDecimal totalAmount,
            List<PaymentOrderOption> options
    ) {
    }

    public record PaymentOrderOption(String groupName, String optionName) {
    }

    /** 결제 승인과 완료 처리에서만 사용하는 최소 주문 스냅샷이다. */
    public record PaymentExecutionOrder(
            long orderId,
            OrderType orderType,
            BigDecimal amount,
            LocalDateTime paymentExpiresAt,
            List<PaymentProduct> products
    ) {

        /** 기존 일반 주문 호출부의 호환용 생성자다. */
        public PaymentExecutionOrder(
                long orderId,
                BigDecimal amount,
                LocalDateTime paymentExpiresAt,
                List<PaymentProduct> products
        ) {
            this(orderId, OrderType.GENERAL, amount, paymentExpiresAt, products);
        }
    }

    /** 결제 완료 시 재고를 차감할 주문 상품이다. */
    public record PaymentProduct(long orderItemId, long productId, int quantity) {
    }
}
