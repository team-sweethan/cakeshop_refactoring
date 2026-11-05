package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.payment.mapper.PaymentOrderMapper;
import org.junit.jupiter.api.Test;

class PaymentOrderQueryServiceTests {

    private final PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    private final PaymentOrderQueryService service = new PaymentOrderQueryService(mapper);

    @Test
    void isUnderGatewayReview_delegatesToPaymentOwnedRule() {
        when(mapper.existsPaymentUnderGatewayReview(10L)).thenReturn(true);

        assertThat(service.isUnderGatewayReview(10L)).isTrue();
        assertThat(service.isUnderGatewayReview(11L)).isFalse();
    }

    @Test
    void isUnderGatewayReview_invalidOrderId_isFalseWithoutQuery() {
        assertThat(service.isUnderGatewayReview(0L)).isFalse();
        verify(mapper, never()).existsPaymentUnderGatewayReview(anyLong());
    }
}
