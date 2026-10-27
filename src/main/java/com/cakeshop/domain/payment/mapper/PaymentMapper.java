package com.cakeshop.domain.payment.mapper;

import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.dto.view.PaymentAdminPaymentRow;
import com.cakeshop.domain.payment.dto.view.PaymentAdminSummaryView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface PaymentMapper {

    List<PaymentAdminPaymentRow> findPaymentsForAdmin(@Param("status") PaymentStatus status);

    PaymentAdminSummaryView summarizePaymentsForAdmin();

    // 결제 대기 주문에 금액이 일치하는 결제 시도를 생성하고 상태를 READY로 고정한다.
    int insertReadyPayment(Payment payment);

    // 결제 ID로 한 건을 조회한다.
    Optional<Payment> findPaymentById(@Param("paymentId") long paymentId);

    // 주문의 현재 DONE 결제를 조회한다. DB 제약에 따라 최대 한 건이다.
    Optional<Payment> findDonePaymentByOrderId(@Param("orderId") long orderId);

    // 주문의 현재 READY 결제 시도를 조회한다. DB 제약에 따라 최대 한 건이다.
    Optional<Payment> findReadyPaymentByOrderId(@Param("orderId") long orderId);

    // 결제 대기 주문의 READY 결제에만 승인 결과와 승인 시각을 함께 기록한다.
    int completeIfReady(
            @Param("paymentId") long paymentId,
            @Param("paymentKey") String paymentKey,
            @Param("method") String method,
            @Param("approvedAt") LocalDateTime approvedAt
    );

    /** 0원 주문의 READY 결제를 PG 승인 없이 내부 완료로 전환한다. */
    int completeZeroAmountIfReady(
            @Param("paymentId") long paymentId,
            @Param("approvedAt") LocalDateTime approvedAt
    );

    /** 관리자가 결제 만료를 확인한 시각을 기록한다. */
    int markExpirationCheckedIfExpired(@Param("paymentId") long paymentId);

    /** 결제 만료 확인을 취소한다. */
    int clearExpirationCheckedIfExpired(@Param("paymentId") long paymentId);

    // DONE 결제의 전체 취소 결과와 취소 시각을 함께 기록한다.
    int cancelIfDone(
            @Param("paymentId") long paymentId,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    // 결제 취소·환불 요청을 REQUESTED 상태로 생성한다.
    int insertPaymentCancellation(PaymentCancellation cancellation);

    // 같은 취소 요청의 동시 완료를 직렬화하기 위해 행 잠금으로 조회한다.
    Optional<PaymentCancellation> findPaymentCancellationByIdForUpdate(
            @Param("cancellationId") long cancellationId
    );

    // 결제 승인 뒤 내부 처리 실패를 복구하는 시스템 취소 요청을 멱등키로 조회한다.
    Optional<PaymentCancellation> findPaymentCancellationByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey
    );

    // 주문 잠금 후 현재 취소 요청을 잠금 조회해, 오래된 스냅샷에 따른 중복 요청 생성을 막는다.
    Optional<PaymentCancellation> findRequestedCancellationByPaymentId(
            @Param("paymentId") long paymentId
    );

    // 원래 웹 요청과 경쟁하지 않도록 DB 현재 시각보다 1분 이상 오래된 취소만 조회한다.
    List<PaymentCancellation> findRequestedRefundCancellations(@Param("limit") int limit);

    // READY 또는 DONE 결제에 시스템 보상 취소 요청을 한 건만 생성한다.
    int insertCompensationCancellation(PaymentCancellation cancellation);

    // 보상 재처리에 필요한 paymentKey를 READY 또는 DONE 결제에 한 번만 연결한다.
    int attachRecoveryPaymentKey(
            @Param("paymentId") long paymentId,
            @Param("paymentKey") String paymentKey
    );

    // 미승인 보상 요청을 해제할 때 READY 결제에 임시로 연결했던 paymentKey를 제거한다.
    int clearRecoveryPaymentKey(
            @Param("paymentId") long paymentId,
            @Param("paymentKey") String paymentKey
    );

    // 미승인 결제 요청을 해제할 때 다음 paymentKey용 승인 멱등키로 교체한다.
    int rotateReadyPaymentIdempotencyKey(
            @Param("paymentId") long paymentId,
            @Param("paymentKey") String paymentKey,
            @Param("idempotencyKey") String idempotencyKey
    );

    // 미승인으로 해제했던 보상 요청을 이후 실제 승인 보상에 같은 멱등키로 재사용한다.
    int reopenReleasedCompensation(
            @Param("paymentId") long paymentId,
            @Param("idempotencyKey") String idempotencyKey
    );

    // 정상 결제 완료와 경쟁하지 않도록 DB 현재 시각보다 1분 이상 오래된 보상만 조회한다.
    List<PaymentCancellation> findRequestedCompensations(@Param("limit") int limit);

    // REQUESTED 환불과 부모 DONE 결제를 함께 완료·취소 처리한다.
    int completeCancellationIfRequested(
            @Param("cancellationId") long cancellationId,
            @Param("transactionKey") String transactionKey,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    // REQUESTED 환불 요청의 실패 정보를 함께 기록한다.
    int failCancellationIfRequested(
            @Param("cancellationId") long cancellationId,
            @Param("failureCode") String failureCode
    );

    // 승인 호출과 경쟁하지 않도록 DB 현재 시각 기준 1분 유예가 지난 보호 요청만 해제한다.
    int failUnapprovedCompensationIfRequested(@Param("cancellationId") long cancellationId);

    // 시스템 보상 취소 결과를 기록하고 READY 또는 DONE 결제를 CANCELED로 맞춘다.
    int completeCompensationIfRequested(
            @Param("cancellationId") long cancellationId,
            @Param("paymentKey") String paymentKey,
            @Param("transactionKey") String transactionKey,
            @Param("canceledAt") LocalDateTime canceledAt
    );
}
