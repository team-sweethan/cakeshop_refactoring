package com.cakeshop.domain.order.service.customer;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.order.dto.form.customer.OrderCustomCreateForm;
import com.cakeshop.domain.order.dto.view.customer.OrderCreationResult;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMapper;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.cakeshop.domain.order.dto.DonWorryPickupEvent;
import org.springframework.transaction.annotation.Transactional;

/** 고객의 수제 케이크 요청·주문·쿠폰 예약·READY 결제 준비를 하나의 작업으로 처리한다. */
@Service
@RequiredArgsConstructor
public class CustomerCustomOrderService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final long PAYMENT_EXPIRATION_MINUTES = 10L;

    private final PickupAvailabilityPolicy pickupAvailabilityPolicy;
    private final ProductQueryService productQueryService;
    private final OrderOptionValidator orderOptionValidator;
    private final OrderMapper orderMapper;
    private final PaymentOrderPreparationCommandService paymentOrderPreparationCommandService;
    private final MemberService memberService;
    private final MemberCouponQueryService memberCouponQueryService;
    private final CouponOrderCommandService couponOrderCommandService;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /** 수제 주문과 스냅샷, 쿠폰 예약, READY 결제를 한 트랜잭션으로 생성한다. */
    @Transactional
    public OrderCreationResult createCustomOrder(long memberId, OrderCustomCreateForm form) {
        return createCustomOrder(memberId, form, false);
    }

    /** 안내 화면에서 서버가 발급한 1회성 새 주문 의도로만 기존 미결제 주문 검사를 건너뛴다. */
    @Transactional
    public OrderCreationResult createCustomOrderAfterPendingPaymentGuide(
            long memberId,
            OrderCustomCreateForm form
    ) {
        return createCustomOrder(memberId, form, true);
    }

    private OrderCreationResult createCustomOrder(
            long memberId,
            OrderCustomCreateForm form,
            boolean skipPendingPaymentCheck
    ) {
        if (!memberCouponQueryService.lockActiveCouponIssuableMember(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        validateActiveMember(memberId);
        validateForm(form);

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
        PreparedCustomItem preparedItem = prepareCustomItem(form);
        validateDisplayedOriginalAmount(form, preparedItem.totalAmount());
        // 관리자 검토가 지연돼도 결제 완료 주문이 고착되지 않도록, 결제 가능 마지막 시각을 기준으로 확정한다.
        LocalDateTime paymentExpiresAt = now.plusMinutes(PAYMENT_EXPIRATION_MINUTES);
        validatePickupAt(
                form.getPickupAt(),
                paymentExpiresAt,
                preparedItem.product().preparationDays()
        );

        Order order = createOrder(memberId, form, preparedItem.totalAmount(), paymentExpiresAt);
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
        requireOneRow(insertedRows);

        saveOrderItem(order.getId(), preparedItem, form.getLettering());

        if (form.getMemberCouponId() != null) {
            BigDecimal discountAmount = couponOrderCommandService.reserveCouponForOrder(
                    memberId,
                    form.getMemberCouponId(),
                    order.getId(),
                    order.getOriginalAmount()
            );
            BigDecimal finalAmount = order.getOriginalAmount().subtract(discountAmount);
            if (finalAmount.signum() <= 0) {
                throw new BusinessException(OrderErrorCode.INVALID_ORDER_AMOUNT);
            }
            requireOneRow(orderMapper.updateAmountsIfPendingPayment(
                    order.getId(),
                    discountAmount,
                    finalAmount
            ));
            order.setDiscountAmount(discountAmount);
            order.setFinalAmount(finalAmount);
        }

        paymentOrderPreparationCommandService.prepareReadyPayment(
                order.getId(),
                order.getOrderNumber(),
                order.getFinalAmount()
        );
        events.publishEvent(new DonWorryPickupEvent(order.getId(), false));
        return OrderCreationResult.paymentReady(order.getId());
    }

    private void validateActiveMember(long memberId) {
        if (!memberService.isActiveMember(memberId)) {
            throw new BusinessException(OrderErrorCode.MEMBER_NOT_AVAILABLE);
        }
    }

    private void validateForm(OrderCustomCreateForm form) {
        if (form == null
                || isBlank(form.getOrdererName())
                || isBlank(form.getOrdererPhone())
                || isBlank(form.getPickupName())
                || isBlank(form.getPickupPhone())
                || !isUuid(form.getRequestKey())
                || form.getProductId() == null
                || form.getDisplayedOriginalAmount() == null
                || form.getDisplayedOriginalAmount().signum() <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validateDisplayedOriginalAmount(OrderCustomCreateForm form, BigDecimal latestOriginalAmount) {
        if (form.getDisplayedOriginalAmount().compareTo(latestOriginalAmount) != 0) {
            throw new BusinessException(OrderErrorCode.ORDER_AMOUNT_CHANGED);
        }
    }

    private void validatePickupAt(
            LocalDateTime pickupAt,
            LocalDateTime paymentExpiresAt,
            int preparationDays
    ) {
        if (pickupAt == null
                || !pickupAt.isAfter(paymentExpiresAt.plusDays(preparationDays))
                || !pickupAvailabilityPolicy.isAvailable(pickupAt)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private PreparedCustomItem prepareCustomItem(OrderCustomCreateForm form) {
        ProductSalesInfo product = productQueryService.getSalesInfo(form.getProductId());
        if (product.productType() != ProductType.CUSTOM) {
            throw new BusinessException(OrderErrorCode.CUSTOM_PRODUCT_REQUIRED);
        }
        if (!product.available()) {
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
        if (product.basePrice() == null || product.basePrice().signum() < 0
                || product.preparationDays() < 1) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }

        List<ValidatedOption> selectedOptions = orderOptionValidator.validate(
                product.productId(),
                form.getOptionIds()
        );
        OrderAmountCalculator.OrderAmounts amounts = OrderAmountCalculator.calculate(
                product.basePrice(),
                1,
                selectedOptions
        );
        if (amounts.totalAmount().signum() <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_AMOUNT);
        }
        return new PreparedCustomItem(
                product,
                selectedOptions,
                amounts.unitOptionAmount(),
                amounts.totalAmount()
        );
    }

    private Order createOrder(
            long memberId,
            OrderCustomCreateForm form,
            BigDecimal originalAmount,
            LocalDateTime paymentExpiresAt
    ) {
        Order order = new Order();
        order.setOrderNumber("ORD-" + compactUuid());
        order.setMemberId(memberId);
        order.setRequestKey(form.getRequestKey());
        order.setOrderType(OrderType.CUSTOM);
        order.setOrdererName(form.getOrdererName().trim());
        order.setOrdererPhone(form.getOrdererPhone().trim());
        order.setPickupName(form.getPickupName().trim());
        order.setPickupPhone(form.getPickupPhone().trim());
        order.setOriginalAmount(originalAmount);
        order.setDiscountAmount(ZERO);
        order.setFinalAmount(originalAmount);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPickupAt(form.getPickupAt());
        order.setPaymentExpiresAt(paymentExpiresAt);
        order.setRequestMessage(trimToNull(form.getRequestMessage()));
        return order;
    }

    private void saveOrderItem(
            long orderId,
            PreparedCustomItem preparedItem,
            String lettering
    ) {
        ProductSalesInfo product = preparedItem.product();
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(orderId);
        orderItem.setProductId(product.productId());
        orderItem.setProductName(product.productName());
        orderItem.setProductType(ProductType.CUSTOM);
        orderItem.setQuantity(1);
        orderItem.setBasePrice(product.basePrice());
        orderItem.setOptionAmount(preparedItem.optionAmount());
        orderItem.setTotalAmount(preparedItem.totalAmount());
        orderItem.setRequirements(trimToNull(lettering));
        orderItem.setPreparationDays(product.preparationDays());
        requireOneRow(orderMapper.insertOrderItem(orderItem));

        for (ValidatedOption selectedOption : preparedItem.selectedOptions()) {
            OrderItemOption snapshot = new OrderItemOption();
            snapshot.setOrderItemId(orderItem.getId());
            snapshot.setProductOptionId(selectedOption.optionId());
            snapshot.setOptionGroupName(selectedOption.groupName());
            snapshot.setOptionName(selectedOption.optionName());
            snapshot.setAdditionalPrice(selectedOption.additionalPrice());
            requireOneRow(orderMapper.insertOrderItemOption(snapshot));
        }
    }

    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new BusinessException(OrderErrorCode.ORDER_SAVE_FAILED);
        }
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PreparedCustomItem(
            ProductSalesInfo product,
            List<ValidatedOption> selectedOptions,
            BigDecimal optionAmount,
            BigDecimal totalAmount
    ) {
    }
}
