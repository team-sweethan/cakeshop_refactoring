package com.cakeshop.domain.payment.controller;

import com.cakeshop.domain.payment.service.DonWorryCheckoutService;
import com.cakeshop.domain.payment.service.DonWorryCheckoutService.DonWorryCheckoutSession;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import com.cakeshop.global.security.MemberDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : 돈워리 호스팅 결제창 연결
 * 설명 : 결제 수단 모달이 결제 준비를 요청하면 돈워리 결제창 주소를 돌려주고, 모달은 그 주소를 iframe으로 띄운다.
 *        결제창이 승인 후 돌려보낸 값은 return 경로에서 기존 성공 콜백 형식으로 바꿔 넘긴다.
 * ******************************
 */
@Controller
@RequiredArgsConstructor
public class DonWorryCheckoutController {

    private final DonWorryCheckoutService donWorryCheckoutService;

    @PostMapping("/orders/{orderId:\\d+}/payment/donworry/session")
    @ResponseBody
    public ResponseEntity<DonWorryCheckoutSession> prepare(
            @PathVariable("orderId") long orderId,
            @AuthenticationPrincipal MemberDetails member
    ) {
        DonWorryCheckoutSession session =
                donWorryCheckoutService.prepare(requireMemberId(member), member.getUsername(), orderId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
    }

    /** 돈워리 결제창의 성공 복귀. 값은 믿지 않고 이어지는 confirm에서 돈워리 서명 조회로 다시 확인한다. */
    @GetMapping("/orders/{orderId:\\d+}/payment/donworry/return")
    public String returnFromCheckout(
            @PathVariable("orderId") long orderId,
            @RequestParam("paymentKey") String paymentId,
            @RequestParam("orderId") String gatewayOrderId,
            @RequestParam("amount") String amount,
            @AuthenticationPrincipal MemberDetails member
    ) {
        requireMemberId(member);
        return "redirect:" + donWorryCheckoutService.successCallbackUrl(orderId, paymentId, gatewayOrderId, amount);
    }

    private long requireMemberId(MemberDetails member) {
        if (member == null || member.getMemberId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return member.getMemberId();
    }
}
