package com.cakeshop.domain.order.service;

import com.cakeshop.domain.order.dto.form.customer.OrderCartCreateForm;
import com.cakeshop.domain.order.dto.form.customer.OrderCreateForm;
import com.cakeshop.domain.order.dto.form.customer.OrderGeneralCreateForm;
import com.cakeshop.domain.cart.dto.view.CartOrderItemView;
import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.mapper.OrderCartMapper;
import com.cakeshop.domain.order.dto.view.OrderCartItemLink;
import com.cakeshop.domain.order.dto.view.customer.OrderCreationResult;
import com.cakeshop.domain.order.service.checkout.OrderAmountCalculator;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator.ValidatedOption;
import com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy;
import com.cakeshop.domain.payment.service.PaymentOrderPreparationCommandService;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.error.ProductErrorCode;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.cakeshop.domain.order.dto.DonWorryPickupEvent;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 일반 상품 주문 생성과 결제 전후의 주문 상태 변경을 구현한다. */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MAX_UNLIMITED_STOCK_QUANTITY = 10;
    private static final long PAYMENT_EXPIRATION_MINUTES = 10L;

    private final PickupAvailabilityPolicy pickupAvailabilityPolicy;
    private final ProductQueryService productQueryService;
    private final OrderOptionValidator orderOptionValidator;
    private final OrderMapper orderMapper;
    private final PaymentOrderPreparationCommandService paymentOrderPreparationCommandService;
    private final MemberService memberService;
    private final MemberCouponQueryService memberCouponQueryService;
    // 쿠폰 담당자가 제공하는 공개 명령 계약이다. 주문 도메인은 쿠폰 Mapper를 직접 사용하지 않는다.
    private final CouponOrderCommandService couponOrderCommandService;
    private final CartOrderQueryService cartOrderQueryService;
    private final OrderCartMapper orderCartMapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /** 일반 상품 주문, 주문 항목 스냅샷, READY 결제를 하나의 트랜잭션으로 생성한다. */
    @Transactional
    public OrderCreationResult createGeneralOrder(long memberId, OrderGeneralCreateForm form) {
        return createGeneralOrder(memberId, form, false);
    }

    /** 안내 화면에서 서버가 발급한 1회성 새 주문 의도로만 기존 미결제 주문 검사를 건너뛴다. */
    @Transactional
    public OrderCreationResult createGeneralOrderAfterPendingPaymentGuide(
            long memberId,
            OrderGeneralCreateForm form
    ) {
        return createGeneralOrder(memberId, form, true);
    }

    private OrderCreationResult createGeneralOrder(
            long memberId,
            OrderGeneralCreateForm form,
            boolean skipPendingPaymentCheck
    ) {
        if (!memberCouponQueryService.lockActiveCouponIssuableMember(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        validateActiveMember(memberId);
        validateForm(form);

        // 브라우저 재전송이나 네트워크 재시도면 원래 주문을 그대로 돌려준다.
        Order existingOrder = orderMapper.findOrderByMemberIdAndRequestKey(
                memberId,
                form.getRequestKey()
        ).orElse(null);

        if (existingOrder != null) {
            return OrderCreationResult.paymentReady(existingOrder.getId());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (!skipPendingPaymentCheck) {
            Order pendingPaymentOrder = orderMapper.findPendingPaymentOrderByMemberId(memberId, now)
                    .orElse(null);
            if (pendingPaymentOrder != null) {
                return OrderCreationResult.pendingPaymentGuide(pendingPaymentOrder);
            }
        }
        validatePickupAt(form.getPickupAt(), now);

        // 클라이언트가 전달한 가격을 사용하지 않고 현재 상품·옵션 정보로 금액을 다시 계산한다.
        PreparedOrderItem preparedItem = prepareItem(form);
        BigDecimal originalAmount = preparedItem.totalAmount();
        validateDisplayedOriginalAmount(form.getDisplayedOriginalAmount(), originalAmount);

        // 주문과 하위 스냅샷 중 하나라도 저장에 실패하면 전체 트랜잭션을 rollback한다.
        Order order = createOrder(memberId, form, originalAmount, now);
        int insertedRows = orderMapper.insertOrder(order);
        if (order.getId() == null) {
            throw new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED);
        }

        // 같은 회원·요청키의 첫 트랜잭션이 이미 완료됐다면 기존 주문 ID만 반환한다.
        if (orderMapper.existsOrderItemByOrderId(order.getId())) {
            Order persistedOrder = orderMapper.findOrderById(order.getId())
                    .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED));
            if (Long.valueOf(memberId).equals(persistedOrder.getMemberId())
                    && form.getRequestKey().equals(persistedOrder.getRequestKey())) {
                return OrderCreationResult.paymentReady(order.getId());
            }
            throw new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED);
        }
        requireOneRow(insertedRows, OrderErrorCode.ORDER_SAVE_FAILED);

        if (form.getMemberCouponId() != null) {
            // 주문 저장 후 예약해야 applied_order_id에 실제 주문 ID를 기록할 수 있다.
            BigDecimal discountAmount = couponOrderCommandService.reserveCouponForOrder(
                    memberId, form.getMemberCouponId(), order.getId(), originalAmount
            );
            BigDecimal finalAmount = originalAmount.subtract(discountAmount);
            requireOneRow(orderMapper.updateAmountsIfPendingPayment(
                    order.getId(), discountAmount, finalAmount
            ), OrderErrorCode.ORDER_SAVE_FAILED);
            order.setDiscountAmount(discountAmount);
            order.setFinalAmount(finalAmount);
        }

        saveOrderItem(order.getId(), preparedItem);

        paymentOrderPreparationCommandService.prepareReadyPayment(
                order.getId(),
                order.getOrderNumber(),
                order.getFinalAmount()
        );

        events.publishEvent(new DonWorryPickupEvent(order.getId(), false));
        return OrderCreationResult.paymentReady(order.getId());
    }

    /**
     * 장바구니 선택 항목은 화면에서 전달된 상품 정보가 아닌 cart 공개 조회 계약으로 다시 읽는다.
     * 상품·옵션·재고와 총 주문 금액은 주문 생성 직전에 현재 DB 기준으로 재검증한다.
     */
    @Transactional
    public OrderCreationResult createCartOrder(long memberId, OrderCartCreateForm form) {
        return createCartOrder(memberId, form, false);
    }

    /** 안내 화면에서 서버가 발급한 1회성 새 주문 의도로만 기존 미결제 주문 검사를 건너뛴다. */
    @Transactional
    public OrderCreationResult createCartOrderAfterPendingPaymentGuide(
            long memberId,
            OrderCartCreateForm form
    ) {
        return createCartOrder(memberId, form, true);
    }

    private OrderCreationResult createCartOrder(
            long memberId,
            OrderCartCreateForm form,
            boolean skipPendingPaymentCheck
    ) {
        if (!memberCouponQueryService.lockActiveCouponIssuableMember(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        validateActiveMember(memberId);
        validateCartForm(form);

        Order existingOrder = orderMapper.findOrderByMemberIdAndRequestKey(memberId, form.getRequestKey())
                .orElse(null);
        if (existingOrder != null) {
            return OrderCreationResult.paymentReady(existingOrder.getId());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (!skipPendingPaymentCheck) {
            Order pendingPaymentOrder = orderMapper.findPendingPaymentOrderByMemberId(memberId, now)
                    .orElse(null);
            if (pendingPaymentOrder != null) {
                return OrderCreationResult.pendingPaymentGuide(pendingPaymentOrder);
            }
        }
        validatePickupAt(form.getPickupAt(), now);

        List<CartOrderItemView> cartItems = cartOrderQueryService.getSelectedOrderItems(
                memberId, form.getCartItemIds()
        );
        List<PreparedOrderItem> preparedItems = cartItems.stream()
                .map(this::prepareCartItem)
                .toList();
        BigDecimal originalAmount = preparedItems.stream()
                .map(PreparedOrderItem::totalAmount)
                .reduce(ZERO, BigDecimal::add);
        if (originalAmount.signum() <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_AMOUNT);
        }
        validateDisplayedOriginalAmount(form.getDisplayedOriginalAmount(), originalAmount);

        Order order = createOrder(memberId, form, form.getRequestKey(), originalAmount, now);
        int insertedRows = orderMapper.insertOrder(order);
        if (order.getId() == null) {
            throw new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED);
        }
        if (orderMapper.existsOrderItemByOrderId(order.getId())) {
            Order persistedOrder = orderMapper.findOrderById(order.getId())
                    .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED));
            if (Long.valueOf(memberId).equals(persistedOrder.getMemberId())
                    && form.getRequestKey().equals(persistedOrder.getRequestKey())) {
                return OrderCreationResult.paymentReady(order.getId());
            }
            throw new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED);
        }
        requireOneRow(insertedRows, OrderErrorCode.ORDER_SAVE_FAILED);

        if (orderCartMapper.insertOrderCartItems(
                order.getId(),
                cartItems.stream()
                        .map(item -> new OrderCartItemLink(item.cartItemId(), item.quantity()))
                        .toList()
        ) != cartItems.size()) {
            throw new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED);
        }

        if (form.getMemberCouponId() != null) {
            BigDecimal discountAmount = couponOrderCommandService.reserveCouponForOrder(
                    memberId, form.getMemberCouponId(), order.getId(), originalAmount
            );
            BigDecimal finalAmount = originalAmount.subtract(discountAmount);
            requireOneRow(orderMapper.updateAmountsIfPendingPayment(
                    order.getId(), discountAmount, finalAmount
            ), OrderErrorCode.ORDER_SAVE_FAILED);
            order.setDiscountAmount(discountAmount);
            order.setFinalAmount(finalAmount);
        }

        preparedItems.forEach(item -> saveOrderItem(order.getId(), item));
        paymentOrderPreparationCommandService.prepareReadyPayment(
                order.getId(), order.getOrderNumber(), order.getFinalAmount()
        );
        events.publishEvent(new DonWorryPickupEvent(order.getId(), false));
        return OrderCreationResult.paymentReady(order.getId());
    }

    /** 세션 값만 신뢰하지 않고 현재 ACTIVE 회원인지 DB 기준으로 검증한다. */
    private void validateActiveMember(long memberId) {
        if (!memberService.isActiveMember(memberId)) {
            throw new BusinessException(OrderErrorCode.MEMBER_NOT_AVAILABLE);
        }
    }

    private void validatePickupAt(
            LocalDateTime pickupAt,
            LocalDateTime now
    ) {
        if (pickupAt == null
                || !pickupAt.isAfter(now)
                || !pickupAt.isAfter(now.plusMinutes(PAYMENT_EXPIRATION_MINUTES))
                || !pickupAvailabilityPolicy.isAvailable(pickupAt)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validateForm(OrderGeneralCreateForm form) {
        if (form == null
                || isBlank(form.getOrdererName())
                || isBlank(form.getOrdererPhone())
                || isBlank(form.getPickupName())
                || isBlank(form.getPickupPhone())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (!isUuid(form.getRequestKey())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (form.getProductId() == null) {
            throw new BusinessException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }
    }

    private void validateCartForm(OrderCartCreateForm form) {
        if (form == null
                || isBlank(form.getOrdererName())
                || isBlank(form.getOrdererPhone())
                || isBlank(form.getPickupName())
                || isBlank(form.getPickupPhone())
                || form.getCartItemIds() == null
                || form.getCartItemIds().isEmpty()
                || !isUuid(form.getRequestKey())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    /** 표시 시점 이후 가격이 바뀌면 주문을 저장하지 않고 최신 주문서를 다시 보여준다. */
    private void validateDisplayedOriginalAmount(
            BigDecimal displayedOriginalAmount,
            BigDecimal currentOriginalAmount
    ) {
        if (displayedOriginalAmount != null
                && displayedOriginalAmount.compareTo(currentOriginalAmount) != 0) {
            throw new BusinessException(OrderErrorCode.ORDER_AMOUNT_CHANGED);
        }
    }

    /** 현재 상품·옵션 정보를 검증하고 주문 항목 스냅샷에 저장할 값을 계산한다. */
    private PreparedOrderItem prepareItem(OrderGeneralCreateForm form) {
        if (form.getQuantity() == null || form.getQuantity() <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_QUANTITY);
        }

        ProductSalesInfo product =
                productQueryService.getSalesInfo(form.getProductId());
        if (product.productType() != ProductType.GENERAL) {
            throw new BusinessException(OrderErrorCode.GENERAL_PRODUCT_REQUIRED);
        }
        if (product.stockQuantity() == null
                && form.getQuantity() > MAX_UNLIMITED_STOCK_QUANTITY) {
            throw new BusinessException(OrderErrorCode.INVALID_QUANTITY);
        }
        if (product.basePrice() == null || product.basePrice().signum() < 0) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
        validateStock(product, form.getQuantity());

        List<ValidatedOption> selectedOptions =
                orderOptionValidator.validate(
                        product.productId(),
                        form.getOptionIds()
                );

        OrderAmountCalculator.OrderAmounts amounts = OrderAmountCalculator.calculate(
                product.basePrice(), form.getQuantity(), selectedOptions
        );
        if (amounts.totalAmount().signum() <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_AMOUNT);
        }

        return new PreparedOrderItem(
                product,
                form.getQuantity(),
                selectedOptions,
                amounts.unitOptionAmount(),
                amounts.totalAmount(),
                null
        );
    }

    private PreparedOrderItem prepareCartItem(CartOrderItemView item) {
        OrderGeneralCreateForm form = new OrderGeneralCreateForm();
        form.setProductId(item.productId());
        form.setQuantity(item.quantity());
        form.setOptionIds(item.optionIds());
        PreparedOrderItem preparedItem = prepareItem(form);
        return new PreparedOrderItem(
                preparedItem.product(),
                preparedItem.quantity(),
                preparedItem.selectedOptions(),
                preparedItem.optionAmount(),
                preparedItem.totalAmount(),
                item.requirements()
        );
    }

    /**
     * 주문 생성 시점의 재고만 사전 확인한다.
     *
     * <p>실제 재고 차감은 결제 성공 처리에서 원자적으로 수행해야 한다.</p>
     */
    private void validateStock(ProductSalesInfo product, int quantity) {
        Integer stockQuantity = product.stockQuantity();
        if (!product.available()
                || stockQuantity != null && stockQuantity < quantity) {
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
    }

    /** 결제 대기 상태와 결제 만료 시각이 설정된 일반 주문을 구성한다. */
    private Order createOrder(
            long memberId,
            OrderGeneralCreateForm form,
            BigDecimal originalAmount,
            LocalDateTime now
    ) {
        return createOrder(memberId, form, form.getRequestKey(), originalAmount, now);
    }

    private Order createOrder(
            long memberId,
            OrderCreateForm form,
            String requestKey,
            BigDecimal originalAmount,
            LocalDateTime now
    ) {
        Order order = new Order();
        order.setOrderNumber("ORD-" + compactUuid());
        order.setMemberId(memberId);
        order.setRequestKey(requestKey);
        order.setOrderType(OrderType.GENERAL);
        order.setOrdererName(form.getOrdererName().trim());
        order.setOrdererPhone(form.getOrdererPhone().trim());
        order.setPickupName(form.getPickupName().trim());
        order.setPickupPhone(form.getPickupPhone().trim());
        order.setOriginalAmount(originalAmount);
        order.setDiscountAmount(ZERO);
        order.setFinalAmount(originalAmount);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPickupAt(form.getPickupAt());
        order.setPaymentExpiresAt(now.plusMinutes(PAYMENT_EXPIRATION_MINUTES));
        order.setRequestMessage(trimToNull(form.getRequestMessage()));
        return order;
    }

    /** 주문 당시 상품과 선택 옵션 정보를 변경되지 않는 스냅샷으로 저장한다. */
    private void saveOrderItem(long orderId, PreparedOrderItem preparedItem) {
        ProductSalesInfo product = preparedItem.product();
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(orderId);
        orderItem.setProductId(product.productId());
        orderItem.setProductName(product.productName());
        orderItem.setProductType(product.productType());
        orderItem.setQuantity(preparedItem.quantity());
        orderItem.setBasePrice(product.basePrice());
        orderItem.setOptionAmount(preparedItem.optionAmount());
        orderItem.setTotalAmount(preparedItem.totalAmount());
        orderItem.setRequirements(preparedItem.requirements());
        orderItem.setPreparationDays(product.preparationDays());
        requireOneRow(
                orderMapper.insertOrderItem(orderItem),
                OrderErrorCode.ORDER_SAVE_FAILED
        );

        for (ValidatedOption selectedOption : preparedItem.selectedOptions()) {
            OrderItemOption snapshot = new OrderItemOption();
            snapshot.setOrderItemId(orderItem.getId());
            snapshot.setProductOptionId(selectedOption.optionId());
            snapshot.setOptionGroupName(selectedOption.groupName());
            snapshot.setOptionName(selectedOption.optionName());
            snapshot.setAdditionalPrice(selectedOption.additionalPrice());
            requireOneRow(
                    orderMapper.insertOrderItemOption(snapshot),
                    OrderErrorCode.ORDER_SAVE_FAILED
            );
        }
    }

    private void requireOneRow(int affectedRows, OrderErrorCode errorCode) {
        if (affectedRows != 1) {
            throw new BusinessException(errorCode);
        }
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private boolean isUuid(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** 검증된 상품 정보와 서버에서 계산한 주문 항목 금액을 전달한다. */
    private record PreparedOrderItem(
            ProductSalesInfo product,
            int quantity,
            List<ValidatedOption> selectedOptions,
            BigDecimal optionAmount,
            BigDecimal totalAmount,
            String requirements
    ) {
    }
}
