package com.cakeshop.domain.payment.entity;

/** 결제 생성 시 고정해 이후 승인·조회·환불에도 사용하는 PG 식별자다. */
public enum PaymentProvider {
    TOSS,
    OWN;

    public static PaymentProvider fromSetting(String setting) {
        return switch (setting) {
            case "toss" -> TOSS;
            case "own" -> OWN;
            default -> throw new IllegalArgumentException("Unsupported payment gateway selection");
        };
    }
}
