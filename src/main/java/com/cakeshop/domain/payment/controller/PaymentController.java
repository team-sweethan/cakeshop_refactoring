package com.cakeshop.domain.payment.controller;

import com.cakeshop.domain.payment.dto.form.PaymentConfirmForm;
import com.cakeshop.domain.payment.dto.form.PaymentSuccessForm;
import com.cakeshop.domain.payment.service.PaymentFacade;
import com.cakeshop.domain.payment.service.PaymentCheckoutService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import com.cakeshop.global.security.MemberDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentFacade paymentFacade;
    private final PaymentCheckoutService paymentCheckoutService;

    /** 회원 소유의 [결제 대기] 주문을 검증하고 PG 결제 화면에 필요한 정보를 모델에 담음.**/
    @GetMapping("/orders/{orderId:\\d+}/payment")
    public String payment(
            @PathVariable("orderId") long orderId,
            @AuthenticationPrincipal MemberDetails member,
            Model model
    ) {
        model.addAttribute(
                "payment",
                paymentCheckoutService.getCheckout(requireMemberId(member), member.getUsername(), orderId
                )
        );
        return "customer/payment/form";

    }
    /** PG 인증 성공 값을 검증한 뒤 CSRF가 적용되는 내부 POST 화면을 반환한다. */
    @GetMapping("/orders/{orderId:\\d+}/payment/success")
    public String success(
            @PathVariable("orderId") long orderId,
            @Valid @ModelAttribute("successForm") PaymentSuccessForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal MemberDetails member,
            Model model
    ) {
        if (bindingResult.hasErrors()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        paymentCheckoutService.validateSuccessCallback(requireMemberId(member), orderId, form);

        model.addAttribute("orderId", orderId);
        model.addAttribute("paymentForm", form.toConfirmForm());
        return "customer/payment/success";
    }

    /** PG 인증 실패 원문 대신 안전한 안내와 재시도 경로를 제공한다. */
    @GetMapping("/orders/{orderId:\\d+}/payment/fail")
    public String fail(
            @PathVariable("orderId") long orderId,
            @RequestParam(name = "code", required = false) String failureCode,
            @AuthenticationPrincipal MemberDetails member,
            Model model
    ) {
        model.addAttribute(
                "failure",
                paymentCheckoutService.getFailure(
                        requireMemberId(member),
                        orderId,
                        failureCode
                )
        );
        return "customer/payment/fail";
    }

    // 진짜 결제 승인.
    /** PG 결제 인증 성공 값을 검증하고 주문 유형에 맞게 결제를 완료한다. */
    @PostMapping("/orders/{orderId:\\d+}/payment/confirm")
    public String confirm(
            @PathVariable("orderId") long orderId,
            @Valid @ModelAttribute PaymentConfirmForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal MemberDetails memberDetails
    ) {
        if (bindingResult.hasErrors()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        paymentFacade.confirmPayment(
                requireMemberId(memberDetails),
                orderId,
                form
        );

        return "redirect:/orders/complete?orderId=" + orderId;
    }

    /** 전액 쿠폰 등으로 최종 금액이 0원인 주문을 PG 없이 완료한다. */
    @PostMapping("/orders/{orderId:\\d+}/payment/zero")
    public String completeZeroAmountPayment(
            @PathVariable("orderId") long orderId,
            @AuthenticationPrincipal MemberDetails memberDetails
    ) {
        paymentFacade.completeZeroAmountGeneralPayment(requireMemberId(memberDetails), orderId);
        return "redirect:/orders/complete?orderId=" + orderId;
    }

    @GetMapping("/orders/complete")
    public String complete(
            @RequestParam("orderId") long orderId,
            @AuthenticationPrincipal MemberDetails member,
            Model model
    ) {
        model.addAttribute(
                "completion",
                paymentCheckoutService.getCompletion(
                        requireMemberId(member),
                        orderId
                )
        );
        return "customer/order/complete";
    }

    /** 유효한 아이디인지 검증.**/
    private long requireMemberId(MemberDetails member) {
        if (member == null || member.getMemberId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return member.getMemberId();
    }
}
