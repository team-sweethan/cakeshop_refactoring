package com.cakeshop.domain.order.mapper;

import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemImage;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderMapper가 실제 DB에 주문 데이터를 저장하고 조회하는지 확인하는 통합 테스트다.
 * JdbcTemplate은 FK 부모 데이터 준비에만 사용하고, 실제 테스트는 OrderMapper로 실행한다.
 * @MybatisTest가 테스트 종료 후 트랜잭션을 롤백하므로 데이터는 DB에 남지 않는다.
 */
@MybatisTest
// 내장 DB로 바꾸지 않고 Testcontainers의 MariaDB를 사용한다.
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 각 테스트가 끝나면 JdbcTemplate과 Mapper가 저장한 데이터를 함께 롤백한다.
@Transactional
class OrderMapperTests {

    private final OrderMapper orderMapper;

    // 회원·상품처럼 주문 저장 전에 필요한 FK 부모 데이터만 준비한다.
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    OrderMapperTests(OrderMapper orderMapper, JdbcTemplate jdbcTemplate) {
        this.orderMapper = orderMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // 반복 실행해도 이메일·주문번호 등이 중복되지 않게 붙이는 값이다.
    private String suffix;
    private long memberId;
    private long productId;
    private long productOptionId;

    // 각 테스트 전에 FK로 필요한 회원·상품·상품 옵션을 먼저 만든다.
    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
        insertMember();
        insertProductAndOption();
    }

    // 주문을 INSERT하고 생성된 ID로 다시 SELECT했을 때 같은 값인지 확인한다.
    @Test
    void insertOrderAndFindOrderById() {
        // Given: 저장할 주문 객체 준비
        Order order = newOrder();

        // When: OrderMapper.insertOrder → XML의 insertOrder SQL 실행
        assertThat(orderMapper.insertOrder(order)).isEqualTo(1);
        // useGeneratedKeys로 DB가 만든 orders.id가 객체에 들어와야 한다.
        assertThat(order.getId()).isNotNull();

        // When: OrderMapper.findOrderById → XML의 findOrderById SQL 실행
        Order savedOrder = orderMapper.findOrderById(order.getId())
                .orElseThrow();

        // Then: DB 자동 생성 시각을 제외한 모든 필드가 저장 전 객체와 같아야 한다.
        assertThat(savedOrder)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt")
                .isEqualTo(order);
        // orders.order_type에 Java enum 이름 CUSTOM이 그대로 저장·조회되어야 한다.
        assertThat(savedOrder.getOrderType()).isEqualTo(OrderType.CUSTOM);
        assertThat(savedOrder.getCreatedAt()).isNotNull();
        assertThat(savedOrder.getUpdatedAt()).isNotNull();
    }

    // 주문 ID와 회원 ID가 모두 일치할 때만 소유 주문으로 확인되는지 검증한다.
    @Test
    void existsByIdAndMemberIdChecksOrderOwner() {
        Order order = newOrder();
        orderMapper.insertOrder(order);

        // 같은 주문 ID와 실제 주문 회원 ID이므로 true다.
        assertThat(orderMapper.existsByIdAndMemberId(
                order.getId(),
                memberId
        )).isTrue();

        // 주문 ID는 같지만 다른 회원 ID이므로 false다.
        assertThat(orderMapper.existsByIdAndMemberId(
                order.getId(),
                memberId + 1
        )).isFalse();
    }

    @Test
    void findFulfillmentOrders_filtersStatusAndIncludesAllPickupDates() {
        Order laterReady = newOrder();
        laterReady.setOrderNumber("FULFILLMENT-LATER-" + suffix);
        laterReady.setStatus(OrderStatus.READY_FOR_PICKUP);
        laterReady.setPickupAt(LocalDateTime.of(2026, 8, 10, 16, 0));
        orderMapper.insertOrder(laterReady);

        Order earlierPickedUp = newOrder();
        earlierPickedUp.setOrderNumber("FULFILLMENT-EARLIER-" + suffix);
        earlierPickedUp.setStatus(OrderStatus.PICKED_UP);
        earlierPickedUp.setPickupAt(LocalDateTime.of(2026, 8, 10, 10, 0));
        orderMapper.insertOrder(earlierPickedUp);

        Order pending = newOrder();
        pending.setOrderNumber("FULFILLMENT-PENDING-" + suffix);
        pending.setPickupAt(LocalDateTime.of(2026, 8, 10, 12, 0));
        orderMapper.insertOrder(pending);

        Order anotherDate = newOrder();
        anotherDate.setOrderNumber("FULFILLMENT-ANOTHER-DATE-" + suffix);
        anotherDate.setStatus(OrderStatus.READY_FOR_PICKUP);
        anotherDate.setPickupAt(LocalDateTime.of(2026, 8, 11, 9, 0));
        orderMapper.insertOrder(anotherDate);

        Order futureUnderReview = newOrder();
        futureUnderReview.setOrderNumber("FULFILLMENT-UNDER-REVIEW-" + suffix);
        futureUnderReview.setOrderType(OrderType.CUSTOM);
        futureUnderReview.setStatus(OrderStatus.UNDER_REVIEW);
        futureUnderReview.setPickupAt(LocalDateTime.of(2026, 8, 12, 10, 0));
        orderMapper.insertOrder(futureUnderReview);

        Order futureInProduction = newOrder();
        futureInProduction.setOrderNumber("FULFILLMENT-IN-PRODUCTION-" + suffix);
        futureInProduction.setOrderType(OrderType.CUSTOM);
        futureInProduction.setStatus(OrderStatus.IN_PRODUCTION);
        futureInProduction.setPickupAt(LocalDateTime.of(2026, 8, 12, 11, 0));
        orderMapper.insertOrder(futureInProduction);

        assertThat(orderMapper.countFulfillmentOrders(null)).isEqualTo(5L);
        assertThat(orderMapper.findFulfillmentOrders(null, 2, 0))
                .extracting(Order::getId)
                .containsExactly(
                        futureUnderReview.getId(),
                        futureInProduction.getId()
                );
        assertThat(orderMapper.findFulfillmentOrders(null, 2, 2))
                .extracting(Order::getId)
                .containsExactly(
                        laterReady.getId(),
                        anotherDate.getId()
                );
        assertThat(orderMapper.findFulfillmentOrders(OrderStatus.READY_FOR_PICKUP, 20, 0))
                .extracting(Order::getId)
                .containsExactly(laterReady.getId(), anotherDate.getId());
        assertThat(orderMapper.findFulfillmentPage(
                anotherDate.getId(),
                OrderStatus.READY_FOR_PICKUP,
                1
        )).isEqualTo(2);
        assertThat(orderMapper.findFulfillmentOrders(OrderStatus.UNDER_REVIEW, 20, 0))
                .extracting(Order::getId)
                .containsExactly(futureUnderReview.getId());
        assertThat(orderMapper.findFulfillmentOrders(OrderStatus.IN_PRODUCTION, 20, 0))
                .extracting(Order::getId)
                .containsExactly(futureInProduction.getId());
    }

    @Test
    void findOverduePendingOrderIds_returnsOnlyExpiredPendingOrdersInOrder() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        Order oldest = newOrder();
        oldest.setOrderNumber("OVERDUE-OLDEST-" + suffix);
        oldest.setPaymentExpiresAt(now.minusMinutes(2));
        orderMapper.insertOrder(oldest);

        Order newest = newOrder();
        newest.setOrderNumber("OVERDUE-NEWEST-" + suffix);
        newest.setPaymentExpiresAt(now.minusMinutes(1));
        orderMapper.insertOrder(newest);

        Order future = newOrder();
        future.setOrderNumber("OVERDUE-FUTURE-" + suffix);
        future.setPaymentExpiresAt(now.plusMinutes(1));
        orderMapper.insertOrder(future);

        oldest.setStatus(OrderStatus.EXPIRED);
        jdbcTemplate.update("UPDATE orders SET status = 'EXPIRED' WHERE id = ?", oldest.getId());

        assertThat(orderMapper.findOverduePendingOrderIds(now, 10))
                .containsExactly(newest.getId());
        assertThat(orderMapper.findOverduePendingOrderIds(now, 0)).isEmpty();
    }

    @Test
    void findPendingPaymentOrderByMemberId_returnsEarliestUnexpiredPendingOrderOnly() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        Order later = newOrder();
        later.setOrderNumber("PENDING-LATER-" + suffix);
        later.setPaymentExpiresAt(now.plusMinutes(9));
        orderMapper.insertOrder(later);

        Order earliest = newOrder();
        earliest.setOrderNumber("PENDING-EARLIEST-" + suffix);
        earliest.setPaymentExpiresAt(now.plusMinutes(3));
        orderMapper.insertOrder(earliest);

        Order expired = newOrder();
        expired.setOrderNumber("PENDING-EXPIRED-" + suffix);
        expired.setPaymentExpiresAt(now);
        orderMapper.insertOrder(expired);

        assertThat(orderMapper.findPendingPaymentOrderByMemberId(memberId, now))
                .hasValueSatisfying(order -> {
                    assertThat(order.getId()).isEqualTo(earliest.getId());
                    assertThat(order.getOrderNumber()).isEqualTo(earliest.getOrderNumber());
                    assertThat(order.getPaymentExpiresAt()).isEqualTo(earliest.getPaymentExpiresAt());
                });
        assertThat(orderMapper.findPendingPaymentOrderByMemberId(memberId + 1, now)).isEmpty();
    }

    @Test
    void insertOrder_sameMemberRequestKey_returnsExistingOrderId() {
        String requestKey = java.util.UUID.randomUUID().toString();
        Order first = newOrder();
        first.setRequestKey(requestKey);
        orderMapper.insertOrder(first);

        Order duplicate = newOrder();
        duplicate.setOrderNumber("DUPLICATE-REQUEST-" + suffix);
        duplicate.setRequestKey(requestKey);

        orderMapper.insertOrder(duplicate);

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(orderMapper.findOrderByMemberIdAndRequestKey(memberId, requestKey))
                .hasValueSatisfying(order -> assertThat(order.getId()).isEqualTo(first.getId()));
    }

    @Test
    void customProductionTransitions_recordStatusTimeAndProcessorConditionally() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        LocalDateTime underReviewAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime approvedAt =
                LocalDateTime.of(2026, 8, 1, 12, 5);
        LocalDateTime readyAt = approvedAt.plusMinutes(10);

        assertThat(orderMapper.startProductionIfUnderReview(
                order.getId(),
                memberId,
                approvedAt
        )).isZero();

        assertThat(orderMapper.markUnderReviewAfterPaymentIfPending(
                order.getId(),
                underReviewAt
        )).isZero();
        insertPayment(order.getId(), "DONE", "CUSTOM-APPROVAL");
        assertThat(orderMapper.markUnderReviewAfterPaymentIfPending(
                order.getId(),
                underReviewAt
        )).isEqualTo(1);
        Order underReview = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(underReview.getStatus()).isEqualTo(OrderStatus.UNDER_REVIEW);
        assertThat(underReview.getUnderReviewAt()).isEqualTo(underReviewAt);

        assertThat(orderMapper.startProductionIfUnderReview(
                order.getId(),
                memberId,
                approvedAt
        )).isEqualTo(1);
        Order approved = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(approved.getApprovedBy()).isEqualTo(memberId);
        assertThat(approved.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(approved.getReadyAt()).isNull();

        assertThat(orderMapper.startProductionIfUnderReview(
                order.getId(),
                memberId,
                readyAt.plusMinutes(1)
        )).isZero();
        assertThat(orderMapper.markReadyForPickupIfInProduction(
                order.getId(),
                readyAt
        )).isEqualTo(1);
        assertThat(orderMapper.findOrderById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void markPickedUpIfReady_recordsStatusTimeAndProcessorConditionally() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        LocalDateTime readyAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime pickedUpAt =
                LocalDateTime.of(2026, 8, 10, 14, 5);

        assertThat(orderMapper.markReadyForPickupAfterPaymentIfPending(
                order.getId(),
                readyAt
        )).isZero();
        insertPayment(order.getId(), "DONE", "GENERAL-PICKUP");
        assertThat(orderMapper.markReadyForPickupAfterPaymentIfPending(
                order.getId(),
                readyAt
        )).isEqualTo(1);
        assertThat(orderMapper.markPickedUpIfReady(
                order.getId(),
                memberId,
                pickedUpAt
        )).isEqualTo(1);

        Order pickedUp = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(pickedUp.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(pickedUp.getReadyAt()).isEqualTo(readyAt);
        assertThat(pickedUp.getPickedUpBy()).isEqualTo(memberId);
        assertThat(pickedUp.getPickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(orderMapper.markPickedUpIfReady(
                order.getId(),
                memberId,
                pickedUpAt.plusMinutes(1)
        )).isZero();
    }

    @Test
    void markPickedUpIfReady_requestedCancellation_doesNotAdvanceOrder() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        long paymentId = insertPayment(order.getId(), "DONE", "CANCEL-IN-PROGRESS");
        assertThat(orderMapper.markReadyForPickupAfterPaymentIfPending(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 1)
        )).isEqualTo(1);
        insertRequestedCancellation(paymentId, "PICKUP-GUARD");

        assertThat(orderMapper.markPickedUpIfReady(
                order.getId(),
                memberId,
                LocalDateTime.of(2026, 8, 10, 14, 5)
        )).isZero();
        assertThat(orderMapper.findOrderById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void markPickedUpIfReady_beforePickupTime_advancesOrder() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        insertPayment(order.getId(), "DONE", "FUTURE-PICKUP");
        assertThat(orderMapper.markReadyForPickupAfterPaymentIfPending(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 1)
        )).isEqualTo(1);

        assertThat(orderMapper.markPickedUpIfReady(
                order.getId(),
                memberId,
                LocalDateTime.of(2026, 8, 10, 13, 59)
        )).isEqualTo(1);
        assertThat(orderMapper.findOrderById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PICKED_UP);
    }

    @Test
    void rejectIfUnderReview_recordsReasonTimeAndProcessorConditionally() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        LocalDateTime underReviewAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime rejectedAt =
                LocalDateTime.of(2026, 8, 1, 12, 5);

        long paymentId =
                insertPayment(order.getId(), "DONE", "CUSTOM-REJECTION");
        orderMapper.markUnderReviewAfterPaymentIfPending(
                order.getId(),
                underReviewAt
        );
        assertThat(orderMapper.rejectIfUnderReview(
                order.getId(),
                memberId,
                rejectedAt,
                "환불 전 반려 시도"
        )).isZero();
        cancelPayment(paymentId, rejectedAt);
        assertThat(orderMapper.rejectIfUnderReview(
                order.getId(),
                memberId,
                rejectedAt,
                "제작 일정이 부족합니다."
        )).isEqualTo(1);

        Order rejected = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getRejectedBy()).isEqualTo(memberId);
        assertThat(rejected.getRejectedAt()).isEqualTo(rejectedAt);
        assertThat(rejected.getRejectReason()).isEqualTo("제작 일정이 부족합니다.");
        assertThat(orderMapper.rejectIfUnderReview(
                order.getId(),
                memberId,
                rejectedAt.plusMinutes(1),
                "다시 반려"
        )).isZero();
    }

    @Test
    void cancelCustomIfUnderReview_afterPaymentCancellation_marksOrderCanceled() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        LocalDateTime underReviewAt = LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime canceledAt = underReviewAt.plusMinutes(5);
        long paymentId = insertPayment(order.getId(), "DONE", "CUSTOMER-CUSTOM-CANCEL");
        assertThat(orderMapper.markUnderReviewAfterPaymentIfPending(order.getId(), underReviewAt))
                .isEqualTo(1);

        assertThat(orderMapper.cancelCustomIfUnderReview(
                order.getId(), "CUSTOMER", "단순 변심", canceledAt
        )).isZero();

        cancelPayment(paymentId, canceledAt);
        assertThat(orderMapper.cancelCustomIfUnderReview(
                order.getId(), "CUSTOMER", "단순 변심", canceledAt
        )).isEqualTo(1);
        Order canceled = orderMapper.findOrderById(order.getId()).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.getCanceledBy()).isEqualTo("CUSTOMER");
    }

    @Test
    void cancelAfterPaymentCompensation_customUnderReviewOrder_marksOrderCanceled() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        LocalDateTime underReviewAt = LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime canceledAt = underReviewAt.plusMinutes(5);
        long paymentId = insertPayment(order.getId(), "DONE", "CUSTOM-COMPENSATION");
        assertThat(orderMapper.markUnderReviewAfterPaymentIfPending(order.getId(), underReviewAt))
                .isEqualTo(1);
        jdbcTemplate.update(
                """
                UPDATE payments
                SET status = 'CANCELED',
                    failure_code = 'INTERNAL_COMPLETION_FAILED',
                    canceled_at = ?
                WHERE id = ?
                """,
                canceledAt,
                paymentId
        );

        assertThat(orderMapper.cancelAfterPaymentCompensation(
                order.getId(), canceledAt, "내부 결제 완료 처리 실패"
        )).isEqualTo(1);

        Order canceled = orderMapper.findOrderById(order.getId()).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.getCanceledBy()).isEqualTo("SYSTEM");
        assertThat(canceled.getCanceledAt()).isEqualTo(canceledAt);
    }

    @Test
    void expireIfPendingPayment_recordsStatusAndTimeConditionally() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        LocalDateTime expiredAt =
                LocalDateTime.of(2026, 8, 1, 12, 11);
        long paymentId =
                insertPayment(order.getId(), "READY", "ORDER-EXPIRATION");

        assertThat(orderMapper.expireIfPendingPayment(
                order.getId(),
                expiredAt
        )).isPositive();

        Order expired = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.getExpiredAt()).isEqualTo(expiredAt);
        assertThat(findPaymentStatus(paymentId)).isEqualTo("EXPIRED");
        assertThat(findPaymentFailureCode(paymentId)).isEqualTo("ORDER_EXPIRED");
        assertThat(orderMapper.expireIfPendingPayment(
                order.getId(),
                expiredAt.plusMinutes(1)
        )).isZero();
    }

    @Test
    void expiration_requestedSystemCompensation_keepsOrderAndPaymentPending() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        long paymentId = insertPayment(
                order.getId(),
                "READY",
                "COMPENSATION-NOT-EXPIRED"
        );
        insertRequestedSystemCompensation(paymentId);
        LocalDateTime expiredAt = LocalDateTime.of(2026, 8, 1, 12, 11);

        assertThat(orderMapper.findOverduePendingOrderIds(expiredAt, 10))
                .doesNotContain(order.getId());
        assertThat(orderMapper.expireIfPendingPayment(
                order.getId(),
                expiredAt
        )).isZero();

        Order pendingOrder = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(pendingOrder.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(pendingOrder.getExpiredAt()).isNull();
        assertThat(findPaymentStatus(paymentId)).isEqualTo("READY");
        assertThat(findCancellationStatus(paymentId)).isEqualTo("REQUESTED");
    }

    @Test
    void cancelIfCurrent_customOrder_isNotSupportedInGeneralMvp() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        LocalDateTime underReviewAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime canceledAt =
                LocalDateTime.of(2026, 8, 1, 12, 5);

        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.PENDING_PAYMENT,
                "CUSTOMER",
                "단순 변심",
                canceledAt,
                canceledAt
        )).isZero();

        long paymentId =
                insertPayment(order.getId(), "DONE", "CUSTOM-CANCELLATION");
        orderMapper.markUnderReviewAfterPaymentIfPending(
                order.getId(),
                underReviewAt
        );
        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.UNDER_REVIEW,
                "CUSTOMER",
                "단순 변심",
                canceledAt,
                canceledAt
        )).isZero();
        cancelPayment(paymentId, canceledAt);
        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.UNDER_REVIEW,
                "CUSTOMER",
                "단순 변심",
                canceledAt,
                canceledAt
        )).isZero();

        Order underReview = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(underReview.getStatus()).isEqualTo(OrderStatus.UNDER_REVIEW);
        assertThat(underReview.getCanceledBy()).isNull();
        assertThat(underReview.getCancelReason()).isNull();
        assertThat(underReview.getCanceledAt()).isNull();
    }

    @Test
    void cancelIfCurrent_usesRequestedAtForCutoffAndStoresCompletionTime() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        LocalDateTime readyAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime requestedAt =
                LocalDateTime.of(2026, 8, 1, 12, 5);
        LocalDateTime canceledAt = order.getPickupAt().plusMinutes(1);

        long paymentId =
                insertPayment(order.getId(), "DONE", "GENERAL-CANCELLATION");
        orderMapper.markReadyForPickupAfterPaymentIfPending(
                order.getId(),
                readyAt
        );
        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.READY_FOR_PICKUP,
                "CUSTOMER",
                "환불 전 취소 시도",
                requestedAt,
                canceledAt
        )).isZero();
        cancelPayment(paymentId, canceledAt);
        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.READY_FOR_PICKUP,
                "CUSTOMER",
                "픽업 전 취소",
                requestedAt,
                canceledAt
        )).isEqualTo(1);

        Order canceled = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.getCanceledBy()).isEqualTo("CUSTOMER");
        assertThat(canceled.getCancelReason()).isEqualTo("픽업 전 취소");
        assertThat(canceled.getCanceledAt()).isEqualTo(canceledAt);
    }

    @Test
    void cancelIfCurrent_rejectsGeneralOrderAtOrAfterPickupTime() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        LocalDateTime readyAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);

        long paymentId =
                insertPayment(order.getId(), "DONE", "GENERAL-CUTOFF");
        orderMapper.markReadyForPickupAfterPaymentIfPending(
                order.getId(),
                readyAt
        );
        cancelPayment(paymentId, readyAt.plusMinutes(1));
        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.READY_FOR_PICKUP,
                "CUSTOMER",
                "픽업 시각 취소",
                order.getPickupAt(),
                order.getPickupAt()
        )).isZero();
        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.READY_FOR_PICKUP,
                "CUSTOMER",
                "픽업 이후 취소",
                order.getPickupAt().plusSeconds(1),
                order.getPickupAt().plusSeconds(1)
        )).isZero();

        Order unchanged = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(unchanged.getCanceledAt()).isNull();
        assertThat(unchanged.getCancelReason()).isNull();
        assertThat(unchanged.getCanceledBy()).isNull();
    }

    // 결제 확인 중 = DONE 결제에 보류 시각은 있고 매입 시각은 없음. 판정 전 진행 기록은 처음 한 번만 남는다
    @Test
    void paymentUnderGatewayReview_andProceedRecord() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        long paymentId = insertPayment(order.getId(), "DONE", "REVIEW-PROCEED");
        LocalDateTime proceededAt = LocalDateTime.of(2026, 8, 1, 12, 10);

        // 보류가 아닌 완료 결제는 확인 중이 아니다. (같은 트랜잭션의 MyBatis 조회 캐시를 피하려고 다른 주문으로 확인)
        Order notHeld = newOrder();
        notHeld.setOrderNumber("REVIEW-NOT-HELD-" + suffix);
        notHeld.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(notHeld);
        insertPayment(notHeld.getId(), "DONE", "REVIEW-NOT-HELD");
        assertThat(orderMapper.hasPaymentUnderGatewayReview(notHeld.getId())).isFalse();

        jdbcTemplate.update("UPDATE payments SET gateway_held_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 8, 1, 12, 1), paymentId);
        assertThat(orderMapper.hasPaymentUnderGatewayReview(order.getId())).isTrue();

        assertThat(orderMapper.recordGatewayReviewProceed(order.getId(), memberId, proceededAt)).isEqualTo(1);
        assertThat(orderMapper.recordGatewayReviewProceed(order.getId(), memberId, proceededAt.plusMinutes(5)))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_proceeded_by FROM orders WHERE id = ?", Long.class, order.getId()))
                .isEqualTo(memberId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_proceeded_at FROM orders WHERE id = ?", LocalDateTime.class, order.getId()))
                .isEqualTo(proceededAt);

        jdbcTemplate.update("UPDATE payments SET gateway_captured_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 8, 1, 12, 30), paymentId);
        assertThat(orderMapper.hasPaymentUnderGatewayReview(order.getId())).isFalse();
    }

    // PG(돈워리)가 결제를 확인 중인 일반 주문은 주문 확인 중으로 두고, 승인(매입)되면 픽업 준비로 넘긴다 (DonWorry ADR-0064 4절)
    @Test
    void heldGeneralOrder_staysUnderReviewUntilGatewayCapture() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        LocalDateTime underReviewAt = LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime readyAt = LocalDateTime.of(2026, 8, 1, 12, 20);

        assertThat(orderMapper.markGeneralUnderReviewAfterHeldPaymentIfPending(
                order.getId(),
                underReviewAt
        )).isZero();
        insertPayment(order.getId(), "DONE", "GENERAL-HELD");
        assertThat(orderMapper.markGeneralUnderReviewAfterHeldPaymentIfPending(
                order.getId(),
                underReviewAt
        )).isEqualTo(1);
        Order underReview = orderMapper.findOrderById(order.getId()).orElseThrow();
        assertThat(underReview.getStatus()).isEqualTo(OrderStatus.UNDER_REVIEW);
        assertThat(underReview.getUnderReviewAt()).isEqualTo(underReviewAt);
        assertThat(underReview.getReadyAt()).isNull();
        // 주문 확인 중에는 픽업 완료로 넘어가지 않는다
        assertThat(orderMapper.markPickedUpIfReady(order.getId(), memberId, readyAt)).isZero();

        assertThat(orderMapper.markGeneralReadyForPickupIfUnderReview(
                order.getId(),
                readyAt
        )).isEqualTo(1);
        Order ready = orderMapper.findOrderById(order.getId()).orElseThrow();
        assertThat(ready.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(ready.getReadyAt()).isEqualTo(readyAt);
        assertThat(orderMapper.markGeneralReadyForPickupIfUnderReview(
                order.getId(),
                readyAt.plusMinutes(1)
        )).isZero();
    }

    @Test
    void heldGeneralOrderTransitions_doNotApplyToCustomOrder() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        insertPayment(order.getId(), "DONE", "CUSTOM-HELD");

        assertThat(orderMapper.markGeneralUnderReviewAfterHeldPaymentIfPending(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 1)
        )).isZero();
        assertThat(orderMapper.markUnderReviewAfterPaymentIfPending(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 1)
        )).isEqualTo(1);
        // 수제 주문은 관리자 제작 승인을 거쳐야 하므로 PG 승인만으로 픽업 준비가 되지 않는다
        assertThat(orderMapper.markGeneralReadyForPickupIfUnderReview(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 20)
        )).isZero();
        assertThat(orderMapper.findOrderById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.UNDER_REVIEW);
    }

    @Test
    void markGeneralReadyForPickupIfUnderReview_requestedCancellation_keepsOrderUnderReview() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        long paymentId = insertPayment(order.getId(), "DONE", "HELD-CANCEL-IN-PROGRESS");
        orderMapper.markGeneralUnderReviewAfterHeldPaymentIfPending(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 1)
        );
        insertRequestedCancellation(paymentId, "HELD-RELEASE-GUARD");

        assertThat(orderMapper.markGeneralReadyForPickupIfUnderReview(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 20)
        )).isZero();
        assertThat(orderMapper.findOrderById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.UNDER_REVIEW);
    }

    @Test
    void cancelIfCurrent_heldGeneralOrderUnderReview_cancelsBeforePickupTime() {
        Order order = newOrder();
        order.setOrderType(OrderType.GENERAL);
        orderMapper.insertOrder(order);
        LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 1, 12, 5);
        long paymentId = insertPayment(order.getId(), "DONE", "HELD-GENERAL-CANCEL");
        orderMapper.markGeneralUnderReviewAfterHeldPaymentIfPending(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 1)
        );
        cancelPayment(paymentId, requestedAt);

        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.READY_FOR_PICKUP,
                "CUSTOMER",
                "상태가 다른 취소",
                requestedAt,
                requestedAt
        )).isZero();
        assertThat(orderMapper.cancelIfCurrent(
                order.getId(),
                OrderStatus.UNDER_REVIEW,
                "CUSTOMER",
                "주문 확인 중 취소",
                requestedAt,
                requestedAt
        )).isEqualTo(1);
        Order canceled = orderMapper.findOrderById(order.getId()).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.getCancelReason()).isEqualTo("주문 확인 중 취소");
    }

    @Test
    void expireIfPendingPayment_doesNotExpireOrderWithDonePayment() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        long paymentId =
                insertPayment(order.getId(), "DONE", "DONE-NOT-EXPIRED");

        assertThat(orderMapper.expireIfPendingPayment(
                order.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 11)
        )).isZero();

        assertThat(orderMapper.findOrderById(order.getId())
                .orElseThrow()
                .getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(findPaymentStatus(paymentId)).isEqualTo("DONE");
    }

    @Test
    void expireIfPendingPayment_expiresOrderWithoutPaymentAttempt() {
        Order order = newOrder();
        orderMapper.insertOrder(order);
        LocalDateTime expiredAt =
                LocalDateTime.of(2026, 8, 1, 12, 11);

        assertThat(orderMapper.expireIfPendingPayment(
                order.getId(),
                expiredAt
        )).isEqualTo(1);

        Order expired = orderMapper.findOrderById(order.getId())
                .orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.getExpiredAt()).isEqualTo(expiredAt);
    }

    @Test
    void invalidOrderStatusCannotBeStored() {
        Order order = newOrder();
        orderMapper.insertOrder(order);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE orders SET status = 'INVALID_STATUS' WHERE id = ?",
                order.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    // 주문 하위 데이터 3종을 INSERT하고 orderId로 다시 SELECT하는지 확인한다.
    @Test
    void insertAndFindOrderItemsOptionsAndImages() {
        // FK 부모인 주문부터 저장한다.
        Order order = newOrder();
        orderMapper.insertOrder(order);

        // OrderMapper.insertOrderItem 호출
        OrderItem orderItem = newOrderItem(order.getId());
        assertThat(orderMapper.insertOrderItem(orderItem)).isEqualTo(1);
        assertThat(orderItem.getId()).isNotNull();

        // OrderMapper.insertOrderItemOption 호출
        OrderItemOption option = newOrderItemOption(orderItem.getId());
        assertThat(orderMapper.insertOrderItemOption(option)).isEqualTo(1);
        assertThat(option.getId()).isNotNull();

        // 이미지 정렬 확인을 위해 sortOrder=2 이미지를 먼저 저장할 준비를 한다.
        OrderItemImage secondImage = newOrderItemImage(
                orderItem.getId(),
                "/test/" + suffix + "/second.jpg",
                2
        );
        OrderItemImage firstImage = newOrderItemImage(
                orderItem.getId(),
                "/test/" + suffix + "/first.jpg",
                1
        );

        // OrderMapper.insertOrderItemImage를 두 번 호출한다.
        assertThat(orderMapper.insertOrderItemImage(secondImage)).isEqualTo(1);
        assertThat(orderMapper.insertOrderItemImage(firstImage)).isEqualTo(1);
        assertThat(secondImage.getId()).isNotNull();
        assertThat(firstImage.getId()).isNotNull();

        // OrderMapper.findOrderItemsByOrderId 결과 검증
        assertThat(orderMapper.findOrderItemsByOrderId(order.getId()))
                .singleElement()
                .usingRecursiveComparison()
                .isEqualTo(orderItem);

        // OrderMapper.findOrderItemOptionsByOrderId 결과 검증
        assertThat(orderMapper.findOrderItemOptionsByOrderId(order.getId()))
                .singleElement()
                .usingRecursiveComparison()
                .isEqualTo(option);

        // OrderMapper.findOrderItemImagesByOrderId 결과 조회
        List<OrderItemImage> images =
                orderMapper.findOrderItemImagesByOrderId(order.getId());

        // 저장 순서와 달라도 sort_order 기준으로 1번 → 2번 순서여야 한다.
        assertThat(images)
                .extracting(OrderItemImage::getImageUrl)
                .containsExactly(
                        firstImage.getImageUrl(),
                        secondImage.getImageUrl()
                );
    }

    // 테스트용 Order 객체를 만든다.
    private Order newOrder() {
        Order order = new Order();
        order.setOrderNumber("ORDER-MAPPER-" + suffix);
        order.setMemberId(memberId);
        order.setOrderType(OrderType.CUSTOM);
        order.setOrdererName("주문자");
        order.setOrdererPhone("010-1111-2222");
        order.setPickupName("수령자");
        order.setPickupPhone("010-3333-4444");
        order.setOriginalAmount(BigDecimal.valueOf(45_000));
        order.setDiscountAmount(BigDecimal.valueOf(5_000));
        order.setFinalAmount(BigDecimal.valueOf(40_000));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPickupAt(LocalDateTime.of(2026, 8, 10, 14, 0));
        order.setPaymentExpiresAt(
                LocalDateTime.of(2026, 8, 1, 12, 10)
        );
        order.setRequestMessage("문구는 생일 축하합니다.");
        return order;
    }

    // 테스트용 OrderItem 객체를 만든다.
    private OrderItem newOrderItem(long orderId) {
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(orderId);
        orderItem.setProductId(productId);
        orderItem.setProductName("주문 제작 케이크");
        orderItem.setProductType(ProductType.CUSTOM);
        orderItem.setQuantity(1);
        orderItem.setBasePrice(BigDecimal.valueOf(40_000));
        orderItem.setOptionAmount(BigDecimal.valueOf(5_000));
        orderItem.setTotalAmount(BigDecimal.valueOf(45_000));
        orderItem.setRequirements("분홍색 크림");
        orderItem.setPreparationDays(3);
        return orderItem;
    }

    // 테스트용 OrderItemOption 객체를 만든다.
    private OrderItemOption newOrderItemOption(long orderItemId) {
        OrderItemOption option = new OrderItemOption();
        option.setOrderItemId(orderItemId);
        option.setProductOptionId(productOptionId);
        option.setOptionGroupName("크기");
        option.setOptionName("2호");
        option.setAdditionalPrice(BigDecimal.valueOf(5_000));
        return option;
    }

    // 테스트용 OrderItemImage 객체를 만든다.
    private OrderItemImage newOrderItemImage(long orderItemId, String imageUrl, int sortOrder) {
        OrderItemImage image = new OrderItemImage();
        image.setOrderItemId(orderItemId);
        image.setImageUrl(imageUrl);
        image.setSortOrder(sortOrder);
        return image;
    }

    private long insertPayment(long orderId, String status, String label) {
        String tossOrderId = "ORDER-MAPPER-TOSS-" + label + "-" + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO payments (
                    order_id,
                    toss_order_id,
                    payment_key,
                    idempotency_key,
                    method,
                    amount,
                    status,
                    approved_at
                )
                VALUES (?, ?, ?, ?, 'CARD', 40000, ?, CURRENT_TIMESTAMP(6))
                """,
                orderId,
                tossOrderId,
                "ORDER-MAPPER-PAYMENT-" + label + "-" + suffix,
                "ORDER-MAPPER-IDEMPOTENCY-" + label + "-" + suffix,
                status
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM payments WHERE toss_order_id = ?",
                Long.class,
                tossOrderId
        );
    }

    private void cancelPayment(long paymentId, LocalDateTime canceledAt) {
        jdbcTemplate.update(
                """
                UPDATE payments
                SET status = 'CANCELED',
                    canceled_at = ?
                WHERE id = ?
                  AND status = 'DONE'
                """,
                canceledAt,
                paymentId
        );
    }

    private void insertRequestedCancellation(long paymentId, String label) {
        jdbcTemplate.update(
                """
                INSERT INTO payment_cancellations (
                    payment_id,
                    idempotency_key,
                    cancel_amount,
                    cancel_reason,
                    status
                )
                VALUES (?, ?, 40000, '고객 취소 처리 중', 'REQUESTED')
                """,
                paymentId,
                "ORDER-MAPPER-CANCEL-" + label + "-" + suffix
        );
    }

    private void insertRequestedSystemCompensation(long paymentId) {
        jdbcTemplate.update(
                """
                INSERT INTO payment_cancellations (
                    payment_id,
                    idempotency_key,
                    cancel_amount,
                    cancel_reason,
                    request_type,
                    status
                )
                VALUES (?, ?, 40000, '내부 주문 처리 실패',
                        'SYSTEM_COMPENSATION', 'REQUESTED')
                """,
                paymentId,
                "COMPENSATE-" + paymentId
        );
    }

    private String findPaymentStatus(long paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?",
                String.class,
                paymentId
        );
    }

    private String findPaymentFailureCode(long paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT failure_code FROM payments WHERE id = ?",
                String.class,
                paymentId
        );
    }

    private String findCancellationStatus(long paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_cancellations WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    // orders.member_id FK를 만족시킬 회원을 JdbcTemplate으로 준비한다.
    private void insertMember() {
        String email = "order-mapper-" + suffix + "@example.com";

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
                "주문 테스트 회원",
                "주문테스트",
                "010-0000-0000"
        );

        memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    // 상품 관련 FK를 만족시킬 카테고리·상품·옵션을 JdbcTemplate으로 준비한다.
    private void insertProductAndOption() {
        String categoryCode = "ORDER_MAPPER_" + suffix;

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
                "주문 Mapper 테스트"
        );

        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE code = ?",
                Long.class,
                categoryCode
        );

        String productName = "주문 Mapper 상품 " + suffix;
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
                VALUES (?, ?, '', 40000, 'CUSTOM', 3, 'ACTIVE')
                """,
                categoryId,
                productName
        );

        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE name = ?",
                Long.class,
                productName
        );

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

        long optionGroupId = jdbcTemplate.queryForObject(
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
                optionGroupId
        );

        productOptionId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM product_options
                WHERE option_group_id = ?
                  AND name = '2호'
                """,
                Long.class,
                optionGroupId
        );
    }
}
