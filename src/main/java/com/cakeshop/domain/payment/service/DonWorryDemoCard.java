package com.cakeshop.domain.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제창의 시연용 모의 카드
 * 설명 : DonWorry 프로토타입 v4.2의 SHOP_CARDS(A·B·D·C)와 같은 순서·표시값이다. 카드번호 원문은 없다.
 *        DonWorry에는 회원별 카드 토큰과 마스킹 표시값만 보낸다.
 *        발급국과 한도 초과는 화면 안내용이다. 현재 DonWorry는 카드 발급국을 받지 않고 모의 승인은 항상 성공하므로
 *        D·C도 A·B와 같은 규칙으로 평가된다.
 * ******************************
 */
public enum DonWorryDemoCard {
    A("시연 카드 A (쓰던 카드)", "**** 4821", "KR", "#172554"),
    B("시연 카드 B (처음 쓰는 카드)", "**** 1173", "KR", "#2563EB"),
    D("시연 카드 D (해외 발급 · 미국)", "**** 5520", "US", "#0F766E"),
    C("시연 카드 C (한도 초과)", "**** 9034", "KR", "#64748B");

    private final String label;
    private final String masked;
    private final String issuerCountry;
    private final String color;

    DonWorryDemoCard(String label, String masked, String issuerCountry, String color) {
        this.label = label;
        this.masked = masked;
        this.issuerCountry = issuerCountry;
        this.color = color;
    }

    public static Optional<DonWorryDemoCard> from(String code) {
        for (DonWorryDemoCard card : values()) {
            if (card.name().equals(code)) {
                return Optional.of(card);
            }
        }
        return Optional.empty();
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    public String masked() {
        return masked;
    }

    public String issuerCountry() {
        return issuerCountry;
    }

    public String color() {
        return color;
    }

    /** DonWorry로 보내는 마스킹 표시값. 50자 이하다. */
    public String displayLabel() {
        return "시연 카드 " + name() + " " + masked;
    }

    /**
     * 회원별로 고정된 카드 토큰. 같은 회원이 같은 카드를 쓰면 같은 값이라 DonWorry가 "쓰던 카드"로 본다.
     * 카드번호가 아니며 숫자만으로 된 값이 아니다.
     */
    public String token(long memberId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("cakeshop-demo-card:" + memberId + ":" + name()).getBytes(StandardCharsets.UTF_8));
            return "csdemo_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
        }
    }
}
