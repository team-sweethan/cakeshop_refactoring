package com.cakeshop.domain.payment.dto.view;

import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 관리자 결제 내역 한 행에 필요한 결제·주문·최근 취소 요청 정보다. */
public record PaymentAdminListRow(
        long paymentId,
        long orderId,
        String orderNumber,
        String gatewayOrderId,
        String ordererName,
        String orderTypeLabel,
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

    public String paymentNumber() {
        return "PAY-%06d".formatted(paymentId);
    }

    public String methodLabel() {
        if (method == null || method.isBlank()) {
            return "미확정";
        }
        return switch (method) {
            case "CARD" -> "카드";
            case "VIRTUAL_ACCOUNT" -> "가상계좌";
            case "TRANSFER" -> "계좌이체";
            case "MOBILE_PHONE" -> "휴대폰";
            case "EASY_PAY" -> "간편결제";
            case "GIFT_CERTIFICATE" -> "상품권";
            case "FOREIGN_EASY_PAY" -> "해외 간편결제";
            default -> method;
        };
    }

    public String statusLabel() {
        return switch (status) {
            case READY -> "결제 대기";
            case DONE -> "결제 완료";
            case CANCELED -> "취소 완료";
            case PARTIAL_CANCELED -> "부분 취소";
            case ABORTED -> "결제 중단";
            case EXPIRED -> "결제 만료";
        };
    }

    public String statusClass() {
        return switch (status) {
            case DONE -> "badge--success";
            case READY -> "badge--warning";
            case CANCELED, PARTIAL_CANCELED -> "badge--info";
            case ABORTED, EXPIRED -> "badge--danger";
        };
    }

    public String cancellationStatusLabel() {
        if (cancellationStatus == null) {
            return "-";
        }
        return switch (cancellationStatus) {
            case REQUESTED -> "취소 처리 중";
            case DONE -> "취소 완료";
            case FAILED -> "취소 실패";
        };
    }

    public String cancellationStatusClass() {
        if (cancellationStatus == null) {
            return "";
        }
        return switch (cancellationStatus) {
            case REQUESTED -> "badge--warning";
            case DONE -> "badge--info";
            case FAILED -> "badge--danger";
        };
    }

    public String cancellationSourceLabel() {
        if (cancellationRequestType == null || cancellationRequestType.isBlank()) {
            return "";
        }
        return switch (cancellationRequestType) {
            case "SYSTEM_COMPENSATION" -> "자동 복구";
            case "ADMIN" -> "관리자 처리";
            case "ADMIN_REJECTION" -> "관리자 반려";
            default -> "고객 요청";
        };
    }

    public LocalDateTime processedAt() {
        if (canceledAt != null) {
            return canceledAt;
        }
        if (approvedAt != null) {
            return approvedAt;
        }
        return requestedAt;
    }

    public boolean expirationChecked() {
        return expirationCheckedAt != null;
    }
}
