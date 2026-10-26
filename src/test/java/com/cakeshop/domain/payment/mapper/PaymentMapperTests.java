package com.cakeshop.domain.payment.mapper;

import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.dto.view.PaymentAdminListRow;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import org.apache.ibatis.session.SqlSession;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PaymentMapper가 실제 DB에서 결제를 저장·조회·조건부 변경하는지 확인한다.
 * JdbcTemplate은 결제의 FK 부모인 회원과 주문 준비에만 사용한다.
 */
@MybatisTest
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 각 테스트가 끝나면 JdbcTemplate과 Mapper가 저장한 데이터를 함께 롤백한다.
@Transactional
class PaymentMapperTests {

    private final PaymentMapper paymentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SqlSession sqlSession;

    @Autowired
    PaymentMapperTests(
            PaymentMapper paymentMapper,
            JdbcTemplate jdbcTemplate,
            SqlSession sqlSession
    ) {
        this.paymentMapper = paymentMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.sqlSession = sqlSession;
    }

    private String suffix;
    private long memberId;
    private long productId;
    private long orderId;

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
        memberId = insertMember();
        productId = insertProduct();
        orderId = insertOrder();
        insertOrderItem();
    }

    // READY 결제를 INSERT한 뒤 주문의 현재 결제로 다시 조회한다.
    @Test
    void insertReadyPaymentAndFindReadyPaymentByOrderId() {
        Payment payment = newPayment("READY");

        assertThat(paymentMapper.insertReadyPayment(payment)).isEqualTo(1);
        assertThat(payment.getId()).isNotNull();

        Payment readyPayment = paymentMapper.findReadyPaymentByOrderId(orderId).orElseThrow();

        assertThat(readyPayment.getId()).isEqualTo(payment.getId());
        assertThat(readyPayment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(readyPayment.getGatewayOrderId()).isEqualTo(payment.getGatewayOrderId());
        assertThat(readyPayment.getActivePaymentOrderId()).isEqualTo(orderId);
    }

    @Test
    void findPaymentsForAdmin_filtersStatusAndIncludesLatestCancellation() {
        Payment payment = insertPayment("ADMIN-LIST");
        completePayment(payment, "ADMIN-LIST");
        PaymentCancellation cancellation =
                newPaymentCancellation(payment.getId(), "ADMIN-LIST");
        assertThat(paymentMapper.insertPaymentCancellation(cancellation)).isEqualTo(1);

        assertThat(paymentMapper.findPaymentsForAdmin(PaymentStatus.READY)).isEmpty();
        assertThat(paymentMapper.findPaymentsForAdmin(PaymentStatus.DONE))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.paymentId()).isEqualTo(payment.getId());
                    assertThat(row.orderId()).isEqualTo(orderId);
                    assertThat(row.status()).isEqualTo(PaymentStatus.DONE);
                    assertThat(row.cancellationStatus())
                            .isEqualTo(PaymentCancellationStatus.REQUESTED);
                    assertThat(row.cancellationRequestType()).isEqualTo("CUSTOMER_CANCEL");
                });

        var summary = paymentMapper.summarizePaymentsForAdmin();
        assertThat(summary.totalCount()).isEqualTo(1);
        assertThat(summary.doneCount()).isEqualTo(1);
        assertThat(summary.canceledCount()).isZero();
        assertThat(summary.attentionCount()).isEqualTo(1);
    }

    @Test
    void findPaymentsForAdmin_completedApprovalGuard_isNotAttentionOrLatestCancellation() {
        Payment payment = insertPayment("COMPLETED-GUARD");
        PaymentCancellation guard = new PaymentCancellation();
        guard.setPaymentId(payment.getId());
        guard.setIdempotencyKey("COMPENSATE-" + payment.getId());
        guard.setCancelAmount(payment.getAmount());
        guard.setCancelReason("승인 보호");
        assertThat(paymentMapper.insertCompensationCancellation(guard)).isEqualTo(1);
        completePayment(payment, "COMPLETED-GUARD");
        assertThat(paymentMapper.failCancellationIfRequested(
                guard.getId(),
                "PAYMENT_COMPLETED"
        )).isEqualTo(1);

        assertThat(paymentMapper.findPaymentsForAdmin(PaymentStatus.DONE))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.cancellationStatus()).isNull();
                    assertThat(row.cancellationRequestType()).isNull();
                });
        assertThat(paymentMapper.summarizePaymentsForAdmin().attentionCount()).isZero();
    }

    @Test
    void reopenReleasedCompensation_completedApprovalGuard_reopensForActualCancellation() {
        Payment payment = insertPayment("REOPEN-COMPLETED-GUARD");
        PaymentCancellation guard = new PaymentCancellation();
        guard.setPaymentId(payment.getId());
        guard.setIdempotencyKey("COMPENSATE-" + payment.getId());
        guard.setCancelAmount(payment.getAmount());
        guard.setCancelReason("승인 보호");
        assertThat(paymentMapper.insertCompensationCancellation(guard)).isEqualTo(1);
        completePayment(payment, "REOPEN-COMPLETED-GUARD");
        assertThat(paymentMapper.failCancellationIfRequested(
                guard.getId(),
                "PAYMENT_COMPLETED"
        )).isEqualTo(1);
        LocalDateTime oldRequestedAt = LocalDateTime.of(2026, 8, 1, 12, 0);
        jdbcTemplate.update(
                "UPDATE payment_cancellations SET requested_at = ? WHERE id = ?",
                oldRequestedAt,
                guard.getId()
        );

        assertThat(paymentMapper.reopenReleasedCompensation(
                payment.getId(),
                guard.getIdempotencyKey()
        )).isEqualTo(1);
        assertThat(paymentMapper.findPaymentCancellationByIdForUpdate(guard.getId()))
                .hasValueSatisfying(reopened -> {
                    assertThat(reopened.getStatus()).isEqualTo(PaymentCancellationStatus.REQUESTED);
                    assertThat(reopened.getFailureCode()).isNull();
                    assertThat(reopened.getRequestedAt()).isAfter(oldRequestedAt);
                });
        assertThat(paymentMapper.findRequestedCompensations(10)).isEmpty();
    }

    @Test
    void findRequestedCompensations_onlyReturnsRequestsOlderThanCutoff() {
        Payment payment = insertPayment("RECOVERY-GRACE-PERIOD");
        PaymentCancellation guard = new PaymentCancellation();
        guard.setPaymentId(payment.getId());
        guard.setIdempotencyKey("COMPENSATE-" + payment.getId());
        guard.setCancelAmount(payment.getAmount());
        guard.setCancelReason("승인 보호");
        assertThat(paymentMapper.insertCompensationCancellation(guard)).isEqualTo(1);
        assertThat(paymentMapper.findRequestedCompensations(10)).isEmpty();
        assertThat(paymentMapper.failUnapprovedCompensationIfRequested(
                guard.getId()
        )).isZero();
        jdbcTemplate.update(
                """
                UPDATE payment_cancellations
                SET requested_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE)
                WHERE id = ?
                """,
                guard.getId()
        );

        assertThat(paymentMapper.findRequestedCompensations(10))
                .extracting(PaymentCancellation::getId)
                .containsExactly(guard.getId());
        assertThat(paymentMapper.failUnapprovedCompensationIfRequested(
                guard.getId()
        )).isEqualTo(1);
    }

    @Test
    void findRequestedRefundCancellations_onlyReturnsRequestsOlderThanCutoff() {
        Payment payment = insertPayment("REFUND-GRACE-PERIOD");
        completePayment(payment, "REFUND-GRACE-PERIOD");
        PaymentCancellation cancellation =
                newPaymentCancellation(payment.getId(), "REFUND-GRACE-PERIOD");
        cancellation.setRequestType("CUSTOMER");
        assertThat(paymentMapper.insertPaymentCancellation(cancellation)).isEqualTo(1);
        assertThat(paymentMapper.findRequestedRefundCancellations(10)).isEmpty();
        jdbcTemplate.update(
                """
                UPDATE payment_cancellations
                SET requested_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE)
                WHERE id = ?
                """,
                cancellation.getId()
        );
        // JdbcTemplate 변경 뒤 같은 Mapper 조회가 실제 DB를 다시 읽게 한다.
        sqlSession.clearCache();
        assertThat(paymentMapper.findRequestedRefundCancellations(10))
                .extracting(PaymentCancellation::getId)
                .containsExactly(cancellation.getId());
    }

    // 한 주문에서 READY 결제는 UNIQUE 제약에 따라 한 건만 허용되는지 확인한다.
    @Test
    void onlyOneReadyPaymentIsAllowedPerOrder() {
        insertPayment("FIRST-READY");

        assertThatThrownBy(() -> insertPayment("SECOND-READY"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completeIfReady_recordsApprovalDataConditionally() {
        Payment payment = insertPayment("READY-TO-DONE");
        LocalDateTime approvedAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);

        assertThat(paymentMapper.completeIfReady(
                payment.getId(),
                "PAYMENT-KEY-" + suffix,
                "CARD",
                approvedAt
        )).isEqualTo(1);

        Payment completed = findPayment(payment.getId());
        assertThat(completed.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(completed.getPaymentKey()).isEqualTo("PAYMENT-KEY-" + suffix);
        assertThat(completed.getMethod()).isEqualTo("CARD");
        assertThat(completed.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(completed.getFailureCode()).isNull();

        assertThat(paymentMapper.completeIfReady(
                payment.getId(),
                "OTHER-PAYMENT-KEY-" + suffix,
                "TRANSFER",
                approvedAt.plusMinutes(1)
        )).isZero();
    }

    @Test
    void cancelIfDone_recordsCancellationTimeConditionally() {
        Payment payment = insertPayment("DONE-TO-CANCELED");
        LocalDateTime approvedAt =
                LocalDateTime.of(2026, 8, 1, 12, 1);
        LocalDateTime canceledAt =
                LocalDateTime.of(2026, 8, 1, 12, 5);
        paymentMapper.completeIfReady(
                payment.getId(),
                "PAYMENT-KEY-CANCEL-" + suffix,
                "CARD",
                approvedAt
        );

        assertThat(paymentMapper.cancelIfDone(
                payment.getId(),
                canceledAt
        )).isEqualTo(1);
        Payment canceled = findPayment(payment.getId());
        assertThat(canceled.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.getCanceledAt()).isEqualTo(canceledAt);
        assertThat(paymentMapper.cancelIfDone(
                payment.getId(),
                canceledAt.plusMinutes(1)
        )).isZero();
    }

    @Test
    void cancelIfDone_doesNotCancelReadyPayment() {
        Payment payment = insertPayment("READY-CANNOT-CANCEL");

        assertThat(paymentMapper.cancelIfDone(
                payment.getId(),
                LocalDateTime.of(2026, 8, 1, 12, 5)
        )).isZero();
        assertThat(findPayment(payment.getId()).getStatus())
                .isEqualTo(PaymentStatus.READY);
    }

    @Test
    void expirationCheck_existingExpiredPayment_updatesListAndAttentionSummary() {
        Payment payment = insertPayment("EXPIRED-CHECK");
        jdbcTemplate.update(
                "UPDATE payments SET status = 'EXPIRED' WHERE id = ?",
                payment.getId()
        );

        assertThat(paymentMapper.summarizePaymentsForAdmin().attentionCount()).isEqualTo(1);
        assertThat(paymentMapper.markExpirationCheckedIfExpired(payment.getId())).isEqualTo(1);
        assertThat(paymentMapper.findPaymentsForAdmin(PaymentStatus.EXPIRED))
                .singleElement()
                .satisfies(row -> assertThat(row.expirationCheckedAt()).isNotNull());
        assertThat(paymentMapper.summarizePaymentsForAdmin().attentionCount()).isZero();

        assertThat(paymentMapper.clearExpirationCheckedIfExpired(payment.getId())).isEqualTo(1);
        assertThat(paymentMapper.summarizePaymentsForAdmin().attentionCount()).isEqualTo(1);
    }

    @Test
    void readyPaymentCannotBeCreatedWhenDonePaymentExists() {
        Payment firstPayment = insertPayment("FIRST-DONE");

        completePayment(firstPayment, "FIRST-DONE");
        assertThat(findPayment(firstPayment.getId()).getActivePaymentOrderId())
                .isEqualTo(orderId);

        assertThatThrownBy(() -> insertPayment("SECOND-AFTER-DONE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Java enum에 없는 상태값을 직접 저장해도 DB CHECK 제약이 거부하는지 확인한다.
    @Test
    void invalidPaymentStatusCannotBeStored() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO payments (
                    order_id,
                    toss_order_id,
                    idempotency_key,
                    amount,
                    status
                )
                VALUES (?, ?, ?, 40000, 'INVALID_STATUS')
                """,
                orderId,
                "TOSS-INVALID-" + suffix,
                "IDEMPOTENCY-INVALID-" + suffix
        )).isInstanceOf(DataIntegrityViolationException.class);

        Integer savedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE toss_order_id = ?",
                Integer.class,
                "TOSS-INVALID-" + suffix
        );
        assertThat(savedCount).isZero();
    }

    @Test
    void insertPaymentCancellationAndFindPaymentCancellationByIdForUpdate() {
        Payment payment = insertPayment("CANCELLATION-MAPPER");
        completePayment(payment, "CANCELLATION-MAPPER");
        PaymentCancellation cancellation =
                newPaymentCancellation(payment.getId(), "MAPPER");

        assertThat(paymentMapper.insertPaymentCancellation(cancellation)).isEqualTo(1);
        assertThat(cancellation.getId()).isNotNull();

        PaymentCancellation saved = paymentMapper
                .findPaymentCancellationByIdForUpdate(cancellation.getId())
                .orElseThrow();

        assertThat(saved.getPaymentId()).isEqualTo(payment.getId());
        assertThat(saved.getIdempotencyKey()).isEqualTo(cancellation.getIdempotencyKey());
        assertThat(saved.getCancelAmount()).isEqualByComparingTo(cancellation.getCancelAmount());
        assertThat(saved.getCancelReason()).isEqualTo(cancellation.getCancelReason());
        assertThat(saved.getRequestType()).isEqualTo(cancellation.getRequestType());
        assertThat(saved.getRequestedBy()).isEqualTo(memberId);
        assertThat(saved.getStatus()).isEqualTo(PaymentCancellationStatus.REQUESTED);
        assertThat(saved.getActiveRequestedPaymentId()).isEqualTo(payment.getId());
        assertThat(saved.getRequestedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(paymentMapper.findRequestedCancellationByPaymentId(payment.getId()))
                .hasValueSatisfying(requested -> assertThat(requested.getId())
                        .isEqualTo(cancellation.getId()));
    }

    @Test
    void insertPaymentCancellation_doesNotInsertForReadyPayment() {
        Payment payment = insertPayment("READY-CANCELLATION");
        PaymentCancellation cancellation =
                newPaymentCancellation(payment.getId(), "READY");

        assertThat(paymentMapper.insertPaymentCancellation(cancellation)).isZero();
        assertThat(cancellation.getId()).isNull();

        Integer savedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payment_cancellations
                WHERE idempotency_key = ?
                """,
                Integer.class,
                cancellation.getIdempotencyKey()
        );
        assertThat(savedCount).isZero();
    }

    @Test
    void insertPaymentCancellation_doesNotInsertWhenAmountDiffersFromPayment() {
        Payment payment = insertPayment("INVALID-CANCELLATION-AMOUNT-MAPPER");
        completePayment(payment, "INVALID-CANCELLATION-AMOUNT-MAPPER");
        PaymentCancellation excessive =
                newPaymentCancellation(payment.getId(), "EXCESSIVE");
        excessive.setCancelAmount(BigDecimal.valueOf(50_000));
        PaymentCancellation partial =
                newPaymentCancellation(payment.getId(), "PARTIAL");
        partial.setCancelAmount(BigDecimal.valueOf(30_000));

        assertThat(paymentMapper.insertPaymentCancellation(excessive)).isZero();
        assertThat(excessive.getId()).isNull();
        assertThat(paymentMapper.insertPaymentCancellation(partial)).isZero();
        assertThat(partial.getId()).isNull();

        Integer savedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payment_cancellations
                WHERE payment_id = ?
                """,
                Integer.class,
                payment.getId()
        );
        assertThat(savedCount).isZero();
    }

    @Test
    void completeCancellationIfRequested_recordsTransactionAndTimeConditionally() {
        Payment payment = insertPayment("CANCELLATION-STATUS");
        completePayment(payment, "CANCELLATION-STATUS");
        PaymentCancellation cancellation =
                newPaymentCancellation(payment.getId(), "STATUS");
        paymentMapper.insertPaymentCancellation(cancellation);
        LocalDateTime canceledAt =
                LocalDateTime.of(2026, 8, 1, 12, 5);

        assertThat(paymentMapper.completeCancellationIfRequested(
                cancellation.getId(),
                "CANCEL-TRANSACTION-" + suffix,
                canceledAt
        )).isPositive();

        PaymentCancellation completed = paymentMapper
                .findPaymentCancellationByIdForUpdate(cancellation.getId())
                .orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentCancellationStatus.DONE);
        assertThat(completed.getActiveRequestedPaymentId()).isNull();
        assertThat(completed.getTransactionKey())
                .isEqualTo("CANCEL-TRANSACTION-" + suffix);
        assertThat(completed.getCanceledAt()).isEqualTo(canceledAt);
        assertThat(completed.getFailureCode()).isNull();
        Payment canceledPayment = findPayment(payment.getId());
        assertThat(canceledPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceledPayment.getCanceledAt()).isEqualTo(canceledAt);
        assertThat(paymentMapper.completeCancellationIfRequested(
                cancellation.getId(),
                "OTHER-TRANSACTION-" + suffix,
                canceledAt.plusMinutes(1)
        )).isZero();

        PaymentCancellation duplicate =
                newPaymentCancellation(payment.getId(), "AFTER-COMPLETION");
        assertThat(paymentMapper.insertPaymentCancellation(duplicate)).isZero();
        assertThat(duplicate.getId()).isNull();
    }

    @Test
    void failCancellationIfRequested_recordsFailureDataConditionally() {
        Payment payment = insertPayment("CANCELLATION-FAILURE");
        completePayment(payment, "CANCELLATION-FAILURE");
        PaymentCancellation cancellation =
                newPaymentCancellation(payment.getId(), "FAILURE");
        paymentMapper.insertPaymentCancellation(cancellation);

        assertThat(paymentMapper.failCancellationIfRequested(
                cancellation.getId(),
                "ALREADY_CANCELED"
        )).isEqualTo(1);

        PaymentCancellation failed = paymentMapper
                .findPaymentCancellationByIdForUpdate(cancellation.getId())
                .orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentCancellationStatus.FAILED);
        assertThat(failed.getActiveRequestedPaymentId()).isNull();
        assertThat(failed.getFailureCode()).isEqualTo("ALREADY_CANCELED");
        assertThat(paymentMapper.failCancellationIfRequested(
                cancellation.getId(),
                "OTHER"
        )).isZero();
    }

    @Test
    void onlyOneRequestedCancellationIsAllowedPerPayment() {
        Payment payment = insertPayment("CANCELLATION");
        completePayment(payment, "CANCELLATION");

        assertThat(insertRequestedCancellation(payment.getId(), "FIRST", 40_000, "REQUESTED"))
                .isEqualTo(1);

        Integer generatedColumnAndTimestampCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payment_cancellations
                WHERE payment_id = ?
                  AND active_requested_payment_id = ?
                  AND request_type = 'CUSTOMER_CANCEL'
                  AND requested_by = ?
                  AND updated_at IS NOT NULL
                """,
                Integer.class,
                payment.getId(),
                payment.getId(),
                memberId
        );
        assertThat(generatedColumnAndTimestampCount).isOne();

        assertThatThrownBy(() ->
                insertRequestedCancellation(payment.getId(), "SECOND", 40_000, "REQUESTED")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidPaymentCancellationStatusCannotBeStored() {
        Payment payment = insertPayment("INVALID-CANCELLATION-STATUS");
        completePayment(payment, "INVALID-CANCELLATION-STATUS");

        assertThatThrownBy(() ->
                insertRequestedCancellation(payment.getId(), "INVALID-STATUS", 40_000, "INVALID")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonPositiveCancellationAmountCannotBeStored() {
        Payment payment = insertPayment("INVALID-CANCELLATION-AMOUNT");
        completePayment(payment, "INVALID-CANCELLATION-AMOUNT");

        assertThatThrownBy(() ->
                insertRequestedCancellation(payment.getId(), "ZERO-AMOUNT", 0, "REQUESTED")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Payment insertPayment(String label) {
        Payment payment = newPayment(label);
        paymentMapper.insertReadyPayment(payment);
        return payment;
    }

    private void completePayment(Payment payment, String label) {
        assertThat(paymentMapper.completeIfReady(
                payment.getId(),
                "PAYMENT-KEY-" + label + "-" + suffix,
                "CARD",
                LocalDateTime.of(2026, 8, 1, 12, 1)
        )).isEqualTo(1);
    }

    private Payment newPayment(String label) {
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setGatewayOrderId("TOSS-" + label + "-" + suffix);
        payment.setIdempotencyKey("IDEMPOTENCY-" + label + "-" + suffix);
        payment.setAmount(BigDecimal.valueOf(40_000));
        return payment;
    }

    private PaymentCancellation newPaymentCancellation(long paymentId, String label) {
        PaymentCancellation cancellation = new PaymentCancellation();
        cancellation.setPaymentId(paymentId);
        cancellation.setIdempotencyKey("CANCEL-MAPPER-" + label + "-" + suffix);
        cancellation.setCancelAmount(BigDecimal.valueOf(40_000));
        cancellation.setCancelReason("테스트 환불");
        cancellation.setRequestType("CUSTOMER_CANCEL");
        cancellation.setRequestedBy(memberId);
        return cancellation;
    }

    private int insertRequestedCancellation(
            long paymentId,
            String label,
            long cancelAmount,
            String status
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO payment_cancellations (
                    payment_id,
                    idempotency_key,
                    cancel_amount,
                    cancel_reason,
                    request_type,
                    requested_by,
                    status
                )
                VALUES (?, ?, ?, '테스트 환불', 'CUSTOMER_CANCEL', ?, ?)
                """,
                paymentId,
                "CANCEL-" + label + "-" + suffix,
                cancelAmount,
                memberId,
                status
        );
    }

    private Payment findPayment(long paymentId) {
        return paymentMapper.findPaymentById(paymentId).orElseThrow();
    }

    // 각 테스트가 기존 DB 데이터에 의존하지 않도록 회원을 직접 준비한다.
    private long insertMember() {
        String email = "payment-mapper-" + suffix + "@example.com";
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
                "결제 테스트 회원",
                "결제테스트",
                "010-0000-0000"
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    // 주문 항목 FK에 필요한 카테고리와 상품을 직접 준비한다.
    private long insertProduct() {
        String categoryCode = "PAYMENT_MAPPER_" + suffix;
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
                "결제 Mapper 테스트"
        );

        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE code = ?",
                Long.class,
                categoryCode
        );

        String productName = "결제 Mapper 상품 " + suffix;
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
                VALUES (?, ?, '', 40000, 'GENERAL', 0, 'ACTIVE')
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

    // payments.order_id FK에 필요한 주문을 직접 준비한다.
    private long insertOrder() {
        String orderNumber = "PAYMENT-MAPPER-" + suffix;
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

    // 결제 대상 주문에 포함된 상품도 매 테스트마다 직접 준비한다.
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
                VALUES (?, ?, ?, 'GENERAL', 1, 40000, 0, 40000, 0)
                """,
                orderId,
                productId,
                "결제 Mapper 상품 " + suffix
        );
    }
}
