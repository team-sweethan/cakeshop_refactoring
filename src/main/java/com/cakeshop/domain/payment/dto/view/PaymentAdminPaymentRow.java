package com.cakeshop.domain.payment.dto.view;

import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 관리자 결제 목록을 조합하기 전 결제 도메인이 소유한 행 데이터다. */
public record PaymentAdminPaymentRow(
        long paymentId,
        long orderId,
        String gatewayOrderId,
        BigDecimal amount,
        String method,
        PaymentStatus status,
        PaymentCancellationStatus cancellationStatus,
        String cancellationRequestType,
        LocalDateTime requestedAt,
        LocalDateTime approvedAt,
        LocalDateTime canceledAt,
        LocalDateTime expirationCheckedAt
) {
}
