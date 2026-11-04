package com.cakeshop.domain.payment.service;

import com.cakeshop.domain.payment.dto.view.PaymentCheckoutView;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.infra.DonWorryPaymentClient;
import com.cakeshop.domain.payment.infra.DonWorryPaymentException;
import com.cakeshop.domain.payment.infra.DonWorryPaymentGateway;
import com.cakeshop.domain.payment.infra.DonWorryPaymentProperties;
import com.cakeshop.domain.payment.infra.DonWorryPaymentSessionRequest;
import com.cakeshop.domain.payment.infra.DonWorryPaymentSessionResponse;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : 돈워리 호스팅 결제창 준비와 결과 복귀
 * 설명 : 주문 소유권·결제 기한·금액 검증은 기존 PaymentCheckoutService를 그대로 거친다.
 *        돈워리에 결제를 준비(세션)하고 받은 결제창 주소를 화면 모달의 iframe으로 띄운다.
 *        카드 선택·FDS·보류 표시는 돈워리 결제창이 맡고, 승인되면 기존 성공 콜백 → confirm으로 주문을 완료한다.
 * ******************************
 */
@Service
public class DonWorryCheckoutService {

    // DonWorry는 orderCreatedAt + 10분 안의 요청만 받는다(DonWorry #149).
    private static final Duration DONWORRY_REQUEST_WINDOW = Duration.ofMinutes(10);
    // DonWorry PaymentRiskSignals와 같은 이메일 규칙
    private static final Pattern DONWORRY_EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

    private final PaymentCheckoutService paymentCheckoutService;
    private final ObjectProvider<DonWorryPaymentClient> clientProvider;
    private final ObjectProvider<DonWorryPaymentProperties> propertiesProvider;
    private final Clock clock;

    public DonWorryCheckoutService(
            PaymentCheckoutService paymentCheckoutService,
            ObjectProvider<DonWorryPaymentClient> clientProvider,
            ObjectProvider<DonWorryPaymentProperties> propertiesProvider,
            Clock clock
    ) {
        this.paymentCheckoutService = paymentCheckoutService;
        this.clientProvider = clientProvider;
        this.propertiesProvider = propertiesProvider;
        this.clock = clock;
    }

    /**
     * 돈워리에 결제를 준비하고 결제창 주소를 받는다. 결제창을 열 때마다 새 준비 요청이며,
     * 응답을 못 받으면 같은 키로 한 번 더 보낸다. 같은 주문의 결제 시도 중복은 돈워리가 막는다.
     */
    public DonWorryCheckoutSession prepare(long memberId, String memberEmail, long orderId) {
        PaymentCheckoutView payment = getDonWorryCheckout(memberId, memberEmail, orderId);
        String shopBaseUrl = shopBaseUrl();
        DonWorryPaymentSessionRequest request = new DonWorryPaymentSessionRequest(
                payment.gatewayOrderId(),
                Long.toString(memberId),
                payment.amount().longValueExact(),
                orderCreatedAt(payment),
                payment.orderName(),
                buyerEmail(memberEmail),
                shopBaseUrl + "/orders/" + orderId + "/payment/donworry/return",
                shopBaseUrl + "/orders/" + orderId + "/payment/fail",
                null
        );
        String key = UUID.randomUUID().toString();
        DonWorryPaymentSessionResponse session;
        try {
            session = createWithRetry(request, key);
        } catch (DonWorryPaymentException exception) {
            if (exception.resultUnknown()) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
            }
            throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        }
        URI checkout = URI.create(session.checkoutUrl());
        String origin = checkout.getScheme() + "://" + checkout.getAuthority();
        return new DonWorryCheckoutSession(session.checkoutUrl(), origin);
    }

    /** 돈워리 결제창이 돌려준 성공 값을 CakeShop 결제 키 형식(DW-)으로 바꿔 기존 성공 콜백 주소를 만든다. */
    public String successCallbackUrl(long orderId, String paymentId, String gatewayOrderId, String amount) {
        if (paymentId == null || !paymentId.matches("[1-9][0-9]{0,18}")) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return UriComponentsBuilder.fromPath("/orders/" + orderId + "/payment/success")
                .queryParam("paymentKey", DonWorryPaymentGateway.PAYMENT_KEY_PREFIX + paymentId)
                .queryParam("orderId", gatewayOrderId)
                .queryParam("amount", amount)
                .encode()
                .build()
                .toUriString();
    }

    private DonWorryPaymentSessionResponse createWithRetry(DonWorryPaymentSessionRequest request, String key) {
        DonWorryPaymentClient client = client();
        try {
            return client.createSession(request, key);
        } catch (DonWorryPaymentException exception) {
            if (!exception.resultUnknown()) {
                throw exception;
            }
            // 결과를 모르면 같은 키·같은 본문으로 한 번 더 보낸다. 돈워리는 같은 세션을 돌려준다.
            return client.createSession(request, key);
        }
    }

    private PaymentCheckoutView getDonWorryCheckout(long memberId, String memberEmail, long orderId) {
        PaymentCheckoutView payment = paymentCheckoutService.getCheckout(memberId, memberEmail, orderId);
        if (!payment.donWorryCheckout()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        if (payment.amount() == null || payment.amount().signum() <= 0) {
            // 0원 주문은 기존 결제 화면의 0원 완료로만 처리한다.
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        return payment;
    }

    /**
     * DonWorry 기한(orderCreatedAt + 10분)을 CakeShop 결제 기한과 같게 맞춘다.
     * 결제 기한에서 역산하므로 같은 주문의 모든 시도에서 같은 값이다.
     */
    private Instant orderCreatedAt(PaymentCheckoutView payment) {
        Instant windowStart = payment.paymentExpiresAt()
                .atZone(clock.getZone())
                .toInstant()
                .minus(DONWORRY_REQUEST_WINDOW);
        Instant now = clock.instant();
        // DonWorry는 미래 시각을 거절한다. CakeShop 결제 기한이 10분보다 길 때만 해당한다.
        return windowStart.isAfter(now) ? now : windowStart;
    }

    // 돈워리는 결제 준비 때 이메일 형식이 틀리면 400으로 거절한다(DonWorry PR #425).
    // 같은 규칙(공백 없이 @ 하나)에 맞지 않으면 보내지 않는다 — 이메일은 선택 값이라 빠져도 결제는 된다.
    private String buyerEmail(String memberEmail) {
        if (memberEmail == null || memberEmail.length() > 254 || !DONWORRY_EMAIL.matcher(memberEmail).matches()) {
            return null;
        }
        return memberEmail;
    }

    private String shopBaseUrl() {
        DonWorryPaymentProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null || properties.getShopBaseUrl() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        return properties.getShopBaseUrl().toString().replaceAll("/+$", "");
    }

    private DonWorryPaymentClient client() {
        DonWorryPaymentClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        return client;
    }

    /** 화면이 iframe으로 띄울 결제창 주소와, 결제창이 보내는 메시지를 확인할 출처. */
    public record DonWorryCheckoutSession(String checkoutUrl, String checkoutOrigin) {
    }
}
