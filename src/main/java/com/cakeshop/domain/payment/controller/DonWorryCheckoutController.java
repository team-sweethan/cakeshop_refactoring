package com.cakeshop.domain.payment.controller;

import com.cakeshop.domain.payment.dto.form.DonWorryPayForm;
import com.cakeshop.domain.payment.dto.view.DonWorryPaymentStatusView;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService.DonWorryPaymentResult;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import com.cakeshop.global.security.MemberDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제창 화면
 * 설명 : 카드 선택 → DonWorry 결제 요청 → 결과에 따라 기존 성공·실패 URL 또는 결과 대기 화면으로 보낸다.
 *        성공 URL 이후의 주문 완료는 Toss와 같은 PaymentController.confirm 흐름을 탄다.
 * ******************************
 */
@Controller
@RequiredArgsConstructor
public class DonWorryCheckoutController {

    static final String DEVICE_COOKIE = "cakeshop_device";
    private static final Duration DEVICE_COOKIE_MAX_AGE = Duration.ofDays(365);

    private final DonWorryCheckoutService donWorryCheckoutService;

    @GetMapping("/orders/{orderId:\\d+}/payment/donworry")
    public String window(
            @PathVariable("orderId") long orderId,
            @RequestParam(name = "card", required = false) String card,
            @AuthenticationPrincipal MemberDetails member,
            Model model
    ) {
        model.addAttribute(
                "checkout",
                donWorryCheckoutService.openWindow(requireMemberId(member), member.getUsername(), orderId, card)
        );
        return "customer/payment/donworry";
    }

    @PostMapping("/orders/{orderId:\\d+}/payment/donworry")
    public String pay(
            @PathVariable("orderId") long orderId,
            @Valid @ModelAttribute DonWorryPayForm form,
            BindingResult bindingResult,
            @CookieValue(name = DEVICE_COOKIE, required = false) String deviceId,
            @AuthenticationPrincipal MemberDetails member,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (bindingResult.hasErrors()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        String device = validDeviceId(deviceId) ? deviceId : issueDeviceId(response);
        DonWorryPaymentResult result = donWorryCheckoutService.pay(
                requireMemberId(member),
                member.getUsername(),
                orderId,
                form,
                device,
                request.getRemoteAddr()
        );
        return redirectFor(result);
    }

    /** PENDING·HELD 결과를 기다리는 화면. 결과가 나오면 성공·실패 URL로 보낸다. */
    @GetMapping("/orders/{orderId:\\d+}/payment/donworry/{paymentKey}")
    public String status(
            @PathVariable("orderId") long orderId,
            @PathVariable("paymentKey") String paymentKey,
            @AuthenticationPrincipal MemberDetails member,
            Model model
    ) {
        DonWorryPaymentResult result = donWorryCheckoutService.status(requireMemberId(member), orderId, paymentKey);
        if (!result.inProgress()) {
            return redirectFor(result);
        }
        model.addAttribute("status", new DonWorryPaymentStatusView(
                result.orderId(),
                result.gatewayOrderId(),
                result.paymentKey(),
                result.paymentStatus()
        ));
        return "customer/payment/donworry-status";
    }

    private String redirectFor(DonWorryPaymentResult result) {
        String base = "/orders/" + result.orderId() + "/payment";
        if (result.captured()) {
            return "redirect:" + UriComponentsBuilder.fromPath(base + "/success")
                    .queryParam("paymentKey", result.paymentKey())
                    .queryParam("orderId", result.gatewayOrderId())
                    .queryParam("amount", result.amount().toPlainString())
                    .encode()
                    .build()
                    .toUriString();
        }
        if (result.inProgress()) {
            return "redirect:" + base + "/donworry/" + result.paymentKey();
        }
        return "redirect:" + UriComponentsBuilder.fromPath(base + "/fail")
                .queryParam("code", result.failureCode())
                .encode()
                .build()
                .toUriString();
    }

    private boolean validDeviceId(String deviceId) {
        if (deviceId == null || deviceId.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(deviceId).toString().equals(deviceId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** DonWorry FDS의 기기 식별자. 브라우저마다 한 번 발급하며 다른 용도로 쓰지 않는다. */
    private String issueDeviceId(HttpServletResponse response) {
        String deviceId = UUID.randomUUID().toString();
        ResponseCookie cookie = ResponseCookie.from(DEVICE_COOKIE, deviceId)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(DEVICE_COOKIE_MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return deviceId;
    }

    private long requireMemberId(MemberDetails member) {
        if (member == null || member.getMemberId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return member.getMemberId();
    }
}
