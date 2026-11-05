package com.cakeshop.domain.payment.infra;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 설정은 새 결제에만 적용하고, 기존 결제는 저장된 PG로 보낸다. */
@Component
public class PaymentGatewayRouter {

    private final Map<PaymentProvider, PaymentGateway> gateways = new EnumMap<>(PaymentProvider.class);

    public PaymentGatewayRouter(
            @Value("${app.payment.gateway:toss}") String selected,
            List<PaymentGateway> gateways
    ) {
        for (PaymentGateway gateway : gateways) {
            if (gateway.provider() == null || this.gateways.putIfAbsent(gateway.provider(), gateway) != null) {
                throw new IllegalStateException("Invalid payment gateway registration");
            }
        }
        if (!this.gateways.containsKey(PaymentProvider.fromSetting(selected))) {
            throw new IllegalStateException("Selected payment gateway implementation is unavailable");
        }
    }

    public PaymentGateway forProvider(PaymentProvider provider) {
        PaymentGateway gateway = provider == null ? null : gateways.get(provider);
        if (gateway == null) {
            // 저장된 PG가 없거나 제공자가 누락돼도 다른 PG로 대체하지 않는다.
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        return gateway;
    }

    /** 고객이 결제창에서 고를 수 있는 PG인지 확인한다. 구현체가 없거나 설정이 비어 있으면 false다. */
    public boolean isAvailable(PaymentProvider provider) {
        PaymentGateway gateway = provider == null ? null : gateways.get(provider);
        return gateway != null && gateway.isAvailable();
    }
}
