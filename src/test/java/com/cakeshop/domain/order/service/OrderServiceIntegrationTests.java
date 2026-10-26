package com.cakeshop.domain.order.service;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.order.dto.form.customer.OrderGeneralCreateForm;
import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.mapper.OrderMapper;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.domain.payment.service.PaymentOrderPreparationCommandServiceImpl;
import com.cakeshop.domain.product.dto.view.ProductOptionGroupView;
import com.cakeshop.domain.product.dto.view.ProductOptionItemView;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.product.service.ProductService;
import com.cakeshop.domain.store.dto.view.StoreView;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@MybatisTest
@Import({
        OrderService.class,
        PickupAvailabilityPolicy.class,
        OrderOptionValidator.class,
        PaymentOrderPreparationCommandServiceImpl.class
})
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class OrderServiceIntegrationTests {

    @MockitoBean
    private CouponOrderCommandService couponOrderCommandService;

    @MockitoBean
    private CartOrderQueryService cartOrderQueryService;

    private static final ZoneId TEST_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FIXED_NOW =
            LocalDateTime.of(2026, 7, 31, 10, 0);

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductQueryService productQueryService;

    @MockitoBean
    private ProductService productService;

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
    private long productOptionId;

    @Autowired
    OrderServiceIntegrationTests(
            OrderService orderService,
            OrderMapper orderMapper,
            PaymentMapper paymentMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
        memberId = insertMember();
        when(memberService.isActiveMember(memberId)).thenReturn(true);
        when(memberCouponQueryService.lockActiveCouponIssuableMember(memberId)).thenReturn(true);
        productId = insertProduct();
        productOptionId = insertProductOption();
        stubProductLookup();
        when(storeService.getStoreView()).thenReturn(storeView());
        when(clock.instant())
                .thenReturn(FIXED_NOW.atZone(TEST_ZONE).toInstant());
        when(clock.getZone()).thenReturn(TEST_ZONE);
    }

    @Test
    void createGeneralOrderPersistsCalculatedSnapshotsAndReadyPayment() {
        // 실제 Spring Bean이 트랜잭션 프록시로 감싸져 있어야 전체 저장이 한 트랜잭션에 참여한다.
        assertThat(AopUtils.isAopProxy(orderService)).isTrue();
        OrderGeneralCreateForm form = createForm();

        long orderId = orderService.createGeneralOrder(memberId, form).orderId();

        Order order = orderMapper.findOrderById(orderId).orElseThrow();
        assertThat(order.getMemberId()).isEqualTo(memberId);
        assertThat(order.getOrderType()).isEqualTo(OrderType.GENERAL);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getOriginalAmount()).isEqualByComparingTo("70000");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getFinalAmount()).isEqualByComparingTo("70000");
        assertThat(order.getPaymentExpiresAt())
                .isEqualTo(FIXED_NOW.plusMinutes(10));

        OrderItem savedItem = orderMapper.findOrderItemsByOrderId(orderId)
                .getFirst();
        assertThat(savedItem.getProductId()).isEqualTo(productId);
        assertThat(savedItem.getProductName())
                .isEqualTo("일반 주문 서비스 상품 " + suffix);
        assertThat(savedItem.getBasePrice()).isEqualByComparingTo("30000");
        assertThat(savedItem.getOptionAmount()).isEqualByComparingTo("5000");
        assertThat(savedItem.getQuantity()).isEqualTo(2);
        assertThat(savedItem.getTotalAmount()).isEqualByComparingTo("70000");

        OrderItemOption savedOption =
                orderMapper.findOrderItemOptionsByOrderId(orderId).getFirst();
        assertThat(savedOption.getProductOptionId()).isEqualTo(productOptionId);
        assertThat(savedOption.getOptionGroupName()).isEqualTo("크기");
        assertThat(savedOption.getOptionName()).isEqualTo("2호");
        assertThat(savedOption.getAdditionalPrice()).isEqualByComparingTo("5000");

        Payment payment = paymentMapper.findReadyPaymentByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getAmount()).isEqualByComparingTo("70000");
        assertThat(payment.getGatewayOrderId()).isEqualTo(order.getOrderNumber());
        assertThat(payment.getIdempotencyKey()).startsWith("PAY-");
    }

    @Test
    void createGeneralOrder_sameRequestKey_returnsSameOrderWithoutDuplicates() {
        OrderGeneralCreateForm form = createForm();

        long firstOrderId = orderService.createGeneralOrder(memberId, form).orderId();
        long secondOrderId = orderService.createGeneralOrder(memberId, form).orderId();

        assertThat(secondOrderId).isEqualTo(firstOrderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE member_id = ? AND request_key = ?",
                Long.class,
                memberId,
                form.getRequestKey()
        )).isEqualTo(1L);
        assertThat(orderMapper.findOrderItemsByOrderId(firstOrderId)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id = ?",
                Long.class,
                firstOrderId
        )).isEqualTo(1L);
    }

    private void stubProductLookup() {
        ProductSalesInfo product = new ProductSalesInfo(
                productId,
                "일반 주문 서비스 상품 " + suffix,
                ProductType.GENERAL,
                0,
                true,
                BigDecimal.valueOf(30_000),
                null
        );
        when(productQueryService.getSalesInfo(productId)).thenReturn(product);
        when(productService.getPublicOptionGroups(productId))
                .thenReturn(List.of(new ProductOptionGroupView(
                        1L,
                        "크기",
                        true,
                        "SINGLE",
                        List.of(new ProductOptionItemView(
                                productOptionId,
                                "2호",
                                BigDecimal.valueOf(5_000)
                        ))
                )));
    }

    private OrderGeneralCreateForm createForm() {
        OrderGeneralCreateForm form = new OrderGeneralCreateForm();
        form.setRequestKey(UUID.randomUUID().toString());
        form.setOrdererName("주문자");
        form.setOrdererPhone("010-1111-2222");
        form.setPickupName("수령자");
        form.setPickupPhone("010-3333-4444");
        form.setPickupAt(FIXED_NOW.plusDays(3));
        form.setRequestMessage("초는 빼주세요.");
        form.setProductId(productId);
        form.setQuantity(2);
        form.setOptionIds(List.of(productOptionId));
        return form;
    }

    private StoreView storeView() {
        return new StoreView(
                1L,
                "테스트 매장",
                null,
                null,
                "서울시",
                "02-0000-0000",
                LocalTime.of(9, 0),
                LocalTime.of(20, 0),
                LocalTime.of(9, 0),
                LocalTime.of(20, 0),
                Set.<DayOfWeek>of(),
                "1층",
                LocalTime.of(10, 0),
                LocalTime.of(19, 0),
                60,
                List.of()
        );
    }

    private long insertMember() {
        String email = "order-service-" + suffix + "@example.com";
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
                "주문 서비스 테스트 회원",
                "주문서비스테스트",
                "010-0000-0000"
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    private long insertProduct() {
        String categoryCode = "ORDER_SERVICE_" + suffix;
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
                "주문 서비스 테스트"
        );
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE code = ?",
                Long.class,
                categoryCode
        );

        String productName = "일반 주문 서비스 상품 " + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    category_id,
                    name,
                    description,
                    base_price,
                    product_type,
                    preparation_days,
                    status
                )
                VALUES (?, ?, '', 30000, 'GENERAL', 0, 'ACTIVE')
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

    private long insertProductOption() {
        jdbcTemplate.update(
                """
                INSERT INTO product_option_groups (
                    product_id,
                    name,
                    required,
                    selection_type,
                    sort_order
                )
                VALUES (?, '크기', 1, 'SINGLE', 1)
                """,
                productId
        );
        long groupId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM product_option_groups
                WHERE product_id = ?
                  AND name = '크기'
                """,
                Long.class,
                productId
        );

        jdbcTemplate.update(
                """
                INSERT INTO product_options (
                    option_group_id,
                    name,
                    additional_price,
                    status,
                    sort_order
                )
                VALUES (?, '2호', 5000, 'ACTIVE', 1)
                """,
                groupId
        );

        return jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM product_options
                WHERE option_group_id = ?
                  AND name = '2호'
                """,
                Long.class,
                groupId
        );
    }
}
