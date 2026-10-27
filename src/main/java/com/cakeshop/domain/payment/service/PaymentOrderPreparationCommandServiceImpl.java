package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** 주문에 연결된 READY 결제 생성을 구현한다. */
@Service
public class PaymentOrderPreparationCommandServiceImpl implements PaymentOrderPreparationCommandService {

    private final PaymentMapper paymentMapper;
    private final PaymentProvider selectedProvider;

    public PaymentOrderPreparationCommandServiceImpl(
            PaymentMapper paymentMapper,
            @Value("${app.payment.gateway:toss}") String selected
    ) {
        this.paymentMapper = paymentMapper;
        this.selectedProvider = PaymentProvider.fromSetting(selected);
    }

    @Override
    @Transactional
    public void prepareReadyPayment(
            long orderId,
            String orderNumber,
            BigDecimal amount
    ) {
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setProvider(selectedProvider);
        payment.setGatewayOrderId(orderNumber);
        payment.setIdempotencyKey("PAY-" + UUID.randomUUID());
        payment.setAmount(amount);
        payment.setStatus(PaymentStatus.READY);

        if (paymentMapper.insertReadyPayment(payment) != 1) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_PREPARATION_FAILED);
        }
    }
}
