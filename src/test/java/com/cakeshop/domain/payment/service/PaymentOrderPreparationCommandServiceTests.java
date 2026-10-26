package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.mapper.PaymentMapper;
import com.cakeshop.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOrderPreparationCommandServiceTests {

    @Mock
    private PaymentMapper paymentMapper;

    @InjectMocks
    private PaymentOrderPreparationCommandServiceImpl paymentPreparationService;

    @Test
    void prepareReadyPayment_validOrder_savesReadyPayment() {
        when(paymentMapper.insertReadyPayment(any(Payment.class)))
                .thenReturn(1);

        paymentPreparationService.prepareReadyPayment(
                10L,
                "ORD-001",
                BigDecimal.valueOf(35_000)
        );

        ArgumentCaptor<Payment> paymentCaptor =
                ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).insertReadyPayment(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue()).satisfies(payment -> {
            assertThat(payment.getOrderId()).isEqualTo(10L);
            assertThat(payment.getGatewayOrderId()).isEqualTo("ORD-001");
            assertThat(payment.getIdempotencyKey()).startsWith("PAY-");
            assertThat(payment.getAmount()).isEqualByComparingTo("35000");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        });
    }

    @Test
    void prepareReadyPayment_saveRejected_throwsPreparationFailed() {
        when(paymentMapper.insertReadyPayment(any(Payment.class)))
                .thenReturn(0);

        assertThatThrownBy(() ->
                paymentPreparationService.prepareReadyPayment(
                        10L,
                        "ORD-001",
                        BigDecimal.valueOf(35_000)
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                PaymentErrorCode.PAYMENT_PREPARATION_FAILED
                        )
        );
    }
}
