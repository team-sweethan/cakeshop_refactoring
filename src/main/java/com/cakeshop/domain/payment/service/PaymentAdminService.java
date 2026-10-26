package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.order.dto.view.OrderPaymentAdminView;
import com.cakeshop.domain.order.service.payment.OrderPaymentAdminQueryService;
import com.cakeshop.domain.payment.dto.form.PaymentAdminSearchCondition;
import com.cakeshop.domain.payment.dto.view.PaymentAdminListRow;
import com.cakeshop.domain.payment.dto.view.PaymentAdminListView;
import com.cakeshop.domain.payment.dto.view.PaymentAdminPaymentRow;
import com.cakeshop.domain.payment.dto.view.PaymentAdminSummaryView;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 결제 목록을 결제와 주문의 공개 조회 계약으로 조합한다. */
@Service
@RequiredArgsConstructor
public class PaymentAdminService {

    private final PaymentMapper paymentMapper;
    private final OrderPaymentAdminQueryService orderPaymentAdminQueryService;

    @Transactional(readOnly = true)
    public PaymentAdminListView getPayments(PaymentAdminSearchCondition condition) {
        PaymentStatus selectedStatus = condition == null ? null : condition.getStatus();
        PaymentAdminSummaryView summary = paymentMapper.summarizePaymentsForAdmin();
        List<PaymentAdminPaymentRow> payments = paymentMapper.findPaymentsForAdmin(selectedStatus);
        Map<Long, OrderPaymentAdminView> ordersById = orderPaymentAdminQueryService.getPaymentAdminOrders(
                        payments.stream().map(PaymentAdminPaymentRow::orderId).toList()
                ).stream()
                .collect(java.util.stream.Collectors.toMap(OrderPaymentAdminView::orderId, Function.identity()));

        return new PaymentAdminListView(
                selectedStatus,
                summary,
                payments.stream().map(payment -> toListRow(payment, ordersById)).toList()
        );
    }

    /** 결제 만료 건의 관리자 확인 여부를 변경한다. */
    @Transactional
    public void updateExpirationCheck(long paymentId, boolean checked) {
        Payment payment = paymentMapper.findPaymentById(paymentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (payment.getStatus() != PaymentStatus.EXPIRED) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (checked) {
            paymentMapper.markExpirationCheckedIfExpired(paymentId);
            return;
        }
        paymentMapper.clearExpirationCheckedIfExpired(paymentId);
    }

    private PaymentAdminListRow toListRow(
            PaymentAdminPaymentRow payment,
            Map<Long, OrderPaymentAdminView> ordersById
    ) {
        OrderPaymentAdminView order = ordersById.get(payment.orderId());
        if (order == null) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
        return new PaymentAdminListRow(
                payment.paymentId(),
                payment.orderId(),
                order.orderNumber(),
                payment.gatewayOrderId(),
                order.ordererName(),
                order.orderTypeLabel(),
                payment.amount(),
                payment.method(),
                payment.status(),
                payment.cancellationStatus(),
                payment.cancellationRequestType(),
                payment.requestedAt(),
                payment.approvedAt(),
                payment.canceledAt(),
                payment.expirationCheckedAt()
        );
    }
}
