package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy;
import com.cakeshop.domain.order.service.payment.OrderPaymentCommandService;
import com.cakeshop.domain.order.service.payment.OrderPaymentRecoveryService;
import com.cakeshop.domain.order.service.OrderService;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentExecutionOrder;
import com.cakeshop.domain.order.service.payment.OrderPaymentQueryService.PaymentProduct;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.ApprovalResult;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.product.service.ProductStockService;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import com.cakeshop.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@MybatisTest
@Import({
        PaymentService.class,
        PaymentRecoveryService.class,
        OrderPaymentRecoveryService.class,
        OrderPaymentCommandService.class,
        OrderService.class,
        PickupAvailabilityPolicy.class,
        PaymentOrderPreparationCommandServiceImpl.class,
        ProductStockService.class
})
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class PaymentCompletionIntegrationTests {

    @MockitoBean
    private CouponOrderCommandService couponOrderCommandService;

    @MockitoBean
    private CartOrderQueryService cartOrderQueryService;

    private static final LocalDateTime APPROVED_AT =
            LocalDateTime.of(2026, 8, 1, 12, 0);

    private final PaymentService paymentService;
    private final PaymentRecoveryService paymentRecoveryService;
    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductQueryService productQueryService;

    @MockitoBean
    private OrderOptionValidator orderOptionValidator;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private MemberCouponQueryService memberCouponQueryService;

    private String suffix;
    private long memberId;
    private long productId;
    private long orderId;
    private long orderItemId;

    @Autowired
    PaymentCompletionIntegrationTests(
            PaymentService paymentService,
            PaymentRecoveryService paymentRecoveryService,
            PaymentMapper paymentMapper,
            OrderMapper orderMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.paymentService = paymentService;
        this.paymentRecoveryService = paymentRecoveryService;
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(APPROVED_AT.atZone(ZoneId.of("Asia/Seoul")).toInstant());
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        suffix = Long.toString(System.nanoTime());
        memberId = insertMember();
        productId = insertProduct();
        orderId = insertOrder();
        insertOrderItem();
        insertReadyPayment();
    }

    @Test
    void completeGeneralPayment_readyPayment_updatesStockPaymentAndOrder() {
        assertThat(AopUtils.isAopProxy(paymentService)).isTrue();
        Payment readyPayment = paymentService.getReadyPayment(orderId);
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                orderId,
                BigDecimal.valueOf(40_000),
                APPROVED_AT.plusMinutes(10),
                List.of(new PaymentProduct(orderItemId, productId, 2))
        );
        ApprovalResult approval = new ApprovalResult(
                "PAYMENT-KEY-" + suffix,
                readyPayment.getGatewayOrderId(),
                "CARD",
                PaymentGatewayStatus.DONE,
                40_000,
                APPROVED_AT
        );

        paymentService.completePayment(order, readyPayment, approval);

        Integer stockQuantity = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class,
                productId
        );
        assertThat(stockQuantity).isEqualTo(3);

        Payment completedPayment = paymentMapper.findPaymentById(readyPayment.getId()).orElseThrow();
        assertThat(completedPayment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(completedPayment.getPaymentKey())
                .isEqualTo(approval.paymentKey());
        assertThat(completedPayment.getMethod()).isEqualTo("CARD");
        assertThat(completedPayment.getApprovedAt()).isEqualTo(APPROVED_AT);

        Order completedOrder = orderMapper.findOrderById(orderId).orElseThrow();
        assertThat(completedOrder.getStatus())
                .isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(completedOrder.getReadyAt()).isEqualTo(APPROVED_AT);
        LocalDateTime stockDeductedAt = jdbcTemplate.queryForObject(
                "SELECT stock_deducted_at FROM order_items WHERE id = ?",
                LocalDateTime.class,
                orderItemId
        );
        assertThat(stockDeductedAt).isEqualTo(APPROVED_AT);
    }

    @Test
    void completePayment_customReadyPayment_updatesStockPaymentAndUnderReview() {
        jdbcTemplate.update(
                "UPDATE products SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?",
                productId
        );
        jdbcTemplate.update("UPDATE orders SET order_type = 'CUSTOM' WHERE id = ?", orderId);
        jdbcTemplate.update(
                "UPDATE order_items SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?",
                orderItemId
        );

        Payment readyPayment = paymentService.getReadyPayment(orderId);
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                orderId,
                OrderType.CUSTOM,
                BigDecimal.valueOf(40_000),
                APPROVED_AT.plusMinutes(10),
                List.of(new PaymentProduct(orderItemId, productId, 2))
        );
        ApprovalResult approval = new ApprovalResult(
                "CUSTOM-PAYMENT-KEY-" + suffix,
                readyPayment.getGatewayOrderId(),
                "CARD",
                PaymentGatewayStatus.DONE,
                40_000,
                APPROVED_AT
        );

        paymentService.completePayment(order, readyPayment, approval);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class,
                productId
        )).isEqualTo(3);
        assertThat(paymentMapper.findDonePaymentByOrderId(orderId))
                .hasValueSatisfying(payment -> assertThat(payment.getStatus())
                        .isEqualTo(PaymentStatus.DONE));
        Order completedOrder = orderMapper.findOrderById(orderId).orElseThrow();
        assertThat(completedOrder.getStatus()).isEqualTo(OrderStatus.UNDER_REVIEW);
        assertThat(completedOrder.getUnderReviewAt()).isEqualTo(APPROVED_AT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_deducted_at FROM order_items WHERE id = ?",
                LocalDateTime.class,
                orderItemId
        )).isEqualTo(APPROVED_AT);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void completePayment_customCouponFailure_rollsBackStockPaymentAndOrder() {
        jdbcTemplate.update(
                "UPDATE products SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?",
                productId
        );
        jdbcTemplate.update("UPDATE orders SET order_type = 'CUSTOM' WHERE id = ?", orderId);
        jdbcTemplate.update(
                "UPDATE order_items SET product_type = 'CUSTOM', preparation_days = 1 WHERE id = ?",
                orderItemId
        );
        Payment readyPayment = paymentService.getReadyPayment(orderId);
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                orderId,
                OrderType.CUSTOM,
                BigDecimal.valueOf(40_000),
                APPROVED_AT.plusMinutes(10),
                List.of(new PaymentProduct(orderItemId, productId, 2))
        );
        ApprovalResult approval = new ApprovalResult(
                "CUSTOM-ROLLBACK-KEY-" + suffix,
                readyPayment.getGatewayOrderId(),
                "CARD",
                PaymentGatewayStatus.DONE,
                40_000,
                APPROVED_AT
        );
        doThrow(new BusinessException(PaymentErrorCode.PAYMENT_COMPLETE_FAILED))
                .when(couponOrderCommandService)
                .useReservedCouponForOrder(orderId);

        assertThatThrownBy(() -> paymentService.completePayment(order, readyPayment, approval))
                .isInstanceOf(BusinessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class,
                productId
        )).isEqualTo(5);
        assertThat(paymentMapper.findPaymentById(readyPayment.getId()))
                .hasValueSatisfying(payment -> assertThat(payment.getStatus())
                        .isEqualTo(PaymentStatus.READY));
        assertThat(orderMapper.findOrderById(orderId))
                .hasValueSatisfying(savedOrder -> assertThat(savedOrder.getStatus())
                        .isEqualTo(OrderStatus.PENDING_PAYMENT));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_deducted_at FROM order_items WHERE id = ?",
                LocalDateTime.class,
                orderItemId
        )).isNull();
    }

    @Test
    void completeCompensation_donePayment_cancelsOrderAndRestoresStockIdempotently() {
        Payment readyPayment = paymentService.getReadyPayment(orderId);
        PaymentExecutionOrder order = new PaymentExecutionOrder(
                orderId,
                BigDecimal.valueOf(40_000),
                APPROVED_AT.plusMinutes(10),
                List.of(new PaymentProduct(orderItemId, productId, 2))
        );
        ApprovalResult approval = new ApprovalResult(
                "PAYMENT-KEY-" + suffix,
                readyPayment.getGatewayOrderId(),
                "CARD",
                PaymentGatewayStatus.DONE,
                40_000,
                APPROVED_AT
        );
        paymentService.completePayment(order, readyPayment, approval);
        Payment donePayment = paymentMapper.findDonePaymentByOrderId(orderId)
                .orElseThrow();
        CompensationRequest request = paymentRecoveryService.createRequest(
                donePayment,
                approval.paymentKey()
        );
        CancellationResult cancellation = new CancellationResult(
                PaymentGatewayStatus.CANCELED,
                "COMPENSATION-TRANSACTION-" + suffix,
                APPROVED_AT.plusMinutes(1)
        );

        paymentRecoveryService.prepareCompensation(request);
        paymentRecoveryService.prepareCompensation(request);
        PaymentCancellation requestedCancellation = paymentMapper
                .findPaymentCancellationByIdempotencyKey(request.idempotencyKey())
                .orElseThrow();
        assertThat(requestedCancellation.getStatus())
                .isEqualTo(PaymentCancellationStatus.REQUESTED);
        assertThat(requestedCancellation.getRequestType())
                .isEqualTo("SYSTEM_COMPENSATION");
        assertThat(paymentMapper.findPaymentById(donePayment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.DONE);
        paymentRecoveryService.completeCompensation(request, cancellation);
        paymentRecoveryService.completeCompensation(request, cancellation);

        Payment canceledPayment = paymentMapper.findPaymentById(donePayment.getId()).orElseThrow();
        assertThat(canceledPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceledPayment.getPaymentKey()).isEqualTo(approval.paymentKey());
        assertThat(canceledPayment.getFailureCode())
                .isEqualTo("INTERNAL_COMPLETION_FAILED");
        Order canceledOrder = orderMapper.findOrderById(orderId).orElseThrow();
        assertThat(canceledOrder.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceledOrder.getCanceledBy()).isEqualTo("SYSTEM");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class,
                productId
        )).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_restored_at FROM order_items WHERE id = ?",
                LocalDateTime.class,
                orderItemId
        )).isEqualTo(cancellation.canceledAt());

        PaymentCancellation savedCancellation = paymentMapper
                .findPaymentCancellationByIdempotencyKey(request.idempotencyKey())
                .orElseThrow();
        assertThat(savedCancellation.getStatus())
                .isEqualTo(PaymentCancellationStatus.DONE);
        assertThat(savedCancellation.getTransactionKey())
                .isEqualTo(cancellation.transactionKey());
    }

    @Test
    void completeCompensation_readyPayment_cancelsPendingOrderWithoutStockChange() {
        Payment readyPayment = paymentService.getReadyPayment(orderId);
        String paymentKey = "PAYMENT-KEY-" + suffix;
        CompensationRequest request = paymentRecoveryService.createRequest(
                readyPayment,
                paymentKey
        );
        CancellationResult cancellation = new CancellationResult(
                PaymentGatewayStatus.CANCELED,
                "READY-COMPENSATION-" + suffix,
                APPROVED_AT.plusMinutes(1)
        );

        paymentRecoveryService.prepareCompensation(request);
        assertThat(paymentMapper.findPaymentCancellationByIdempotencyKey(
                request.idempotencyKey()
        )).hasValueSatisfying(saved -> assertThat(saved.getStatus())
                .isEqualTo(PaymentCancellationStatus.REQUESTED));
        assertThat(paymentMapper.findPaymentById(readyPayment.getId())
                .orElseThrow()
                .getPaymentKey()).isEqualTo(paymentKey);
        paymentRecoveryService.completeCompensation(request, cancellation);

        Payment canceledPayment = paymentMapper.findPaymentById(readyPayment.getId()).orElseThrow();
        assertThat(canceledPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceledPayment.getPaymentKey()).isEqualTo(paymentKey);
        assertThat(orderMapper.findOrderById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class,
                productId
        )).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_deducted_at FROM order_items WHERE id = ?",
                LocalDateTime.class,
                orderItemId
        )).isNull();
    }

    @Test
    void prepareCompensation_releasedUnapprovedRequest_reopensSameIdempotencyKey() {
        Payment readyPayment = paymentService.getReadyPayment(orderId);
        String paymentKey = "PAYMENT-KEY-" + suffix;
        CompensationRequest request = paymentRecoveryService.createRequest(
                readyPayment,
                paymentKey
        );

        paymentRecoveryService.prepareCompensation(request);
        jdbcTemplate.update(
                """
                UPDATE payment_cancellations
                SET requested_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE)
                WHERE idempotency_key = ?
                """,
                request.idempotencyKey()
        );
        paymentRecoveryService.releaseUnapprovedCompensation(request);
        Payment releasedPayment = paymentMapper.findPaymentById(readyPayment.getId())
                .orElseThrow();
        assertThat(releasedPayment.getIdempotencyKey())
                .isNotEqualTo(readyPayment.getIdempotencyKey());
        paymentRecoveryService.prepareCompensation(request);

        PaymentCancellation cancellation = paymentMapper
                .findPaymentCancellationByIdempotencyKey(request.idempotencyKey())
                .orElseThrow();
        assertThat(cancellation.getStatus()).isEqualTo(PaymentCancellationStatus.REQUESTED);
        assertThat(paymentMapper.findRequestedCompensations(10)).isEmpty();
        assertThat(paymentMapper.findPaymentById(readyPayment.getId()).orElseThrow().getPaymentKey())
                .isEqualTo(paymentKey);
    }

    @Test
    void releaseUnapprovedCompensation_recentRequest_keepsApprovalGuard() {
        Payment readyPayment = paymentService.getReadyPayment(orderId);
        String paymentKey = "RECENT-PAYMENT-KEY-" + suffix;
        CompensationRequest request = paymentRecoveryService.createRequest(
                readyPayment,
                paymentKey
        );
        paymentRecoveryService.prepareCompensation(request);

        assertThatThrownBy(() -> paymentRecoveryService.releaseUnapprovedCompensation(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(PaymentErrorCode.PAYMENT_RECOVERY_PENDING)
                );

        assertThat(paymentMapper.findPaymentCancellationByIdempotencyKey(
                request.idempotencyKey()
        )).hasValueSatisfying(saved -> assertThat(saved.getStatus())
                .isEqualTo(PaymentCancellationStatus.REQUESTED));
        assertThat(paymentMapper.findPaymentById(readyPayment.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getPaymentKey()).isEqualTo(paymentKey);
                    assertThat(saved.getIdempotencyKey())
                            .isEqualTo(readyPayment.getIdempotencyKey());
                });
    }

    private long insertMember() {
        String email = "payment-completion-" + suffix + "@example.com";
        jdbcTemplate.update(
                """
                INSERT INTO members (
                    email,
                    password,
                    name,
                    nickname,
                    phone,
                    role,
                    status
                )
                VALUES (?, NULL, ?, ?, ?, 'USER', 'ACTIVE')
                """,
                email,
                "결제 완료 테스트 회원",
                "결제완료테스트",
                "010-0000-0000"
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    private long insertProduct() {
        String categoryCode = "PAYMENT_COMPLETION_" + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO categories (
                    code,
                    name,
                    sort_order,
                    is_active
                )
                VALUES (?, ?, 999, 1)
                """,
                categoryCode,
                "결제 완료 테스트"
        );
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE code = ?",
                Long.class,
                categoryCode
        );

        String productName = "결제 완료 테스트 상품 " + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    category_id,
                    name,
                    description,
                    base_price,
                    product_type,
                    preparation_days,
                    stock_quantity,
                    status
                )
                VALUES (?, ?, '', 20000, 'GENERAL', 0, 5, 'ACTIVE')
                """,
                categoryId,
                productName
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE name = ?",
                Long.class,
                productName
        );
    }

    private long insertOrder() {
        String orderNumber = "PAYMENT-COMPLETION-" + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    order_number,
                    member_id,
                    order_type,
                    orderer_name,
                    orderer_phone,
                    pickup_name,
                    pickup_phone,
                    original_amount,
                    discount_amount,
                    final_amount,
                    status,
                    pickup_at,
                    payment_expires_at
                )
                VALUES (?, ?, 'GENERAL', ?, ?, ?, ?, 40000, 0, 40000,
                        'PENDING_PAYMENT',
                        CURRENT_TIMESTAMP(6) + INTERVAL 1 DAY,
                        CURRENT_TIMESTAMP(6) + INTERVAL 10 MINUTE)
                """,
                orderNumber,
                memberId,
                "주문자",
                "010-1111-2222",
                "수령자",
                "010-3333-4444"
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?",
                Long.class,
                orderNumber
        );
    }

    private void insertOrderItem() {
        jdbcTemplate.update(
                """
                INSERT INTO order_items (
                    order_id,
                    product_id,
                    product_name,
                    product_type,
                    quantity,
                    base_price,
                    option_amount,
                    total_amount,
                    preparation_days
                )
                VALUES (?, ?, ?, 'GENERAL', 2, 20000, 0, 40000, 0)
                """,
                orderId,
                productId,
                "결제 완료 테스트 상품 " + suffix
        );
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ?",
                Long.class,
                orderId
        );
    }

    private void insertReadyPayment() {
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setGatewayOrderId("TOSS-PAYMENT-" + suffix);
        payment.setIdempotencyKey("IDEMPOTENCY-PAYMENT-" + suffix);
        payment.setAmount(BigDecimal.valueOf(40_000));

        assertThat(paymentMapper.insertReadyPayment(payment)).isEqualTo(1);
    }
}
