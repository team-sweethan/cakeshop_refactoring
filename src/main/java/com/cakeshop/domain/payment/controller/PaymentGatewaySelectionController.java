package com.cakeshop.domain.payment.controller;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.service.PaymentGatewaySelectionService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import com.cakeshop.global.security.MemberDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : 결제 수단 선택(Toss·DonWorry)
 * 설명 : 결제 화면 모달의 스크립트가 선택만 저장하고 응답을 받은 뒤 해당 결제창을 연다.
 *        Toss는 Toss 결제창, DonWorry는 모달 안 DonWorry 결제창이다.
 * ******************************
 */
@Controller
@RequiredArgsConstructor
public class PaymentGatewaySelectionController {

    private final PaymentGatewaySelectionService selectionService;

    @PostMapping("/orders/{orderId:\\d+}/payment/gateway/donworry")
    @ResponseBody
    public ResponseEntity<Void> selectDonWorry(
            @PathVariable("orderId") long orderId,
            @AuthenticationPrincipal MemberDetails member
    ) {
        selectionService.select(requireMemberId(member), member.getUsername(), orderId, PaymentProvider.OWN);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/orders/{orderId:\\d+}/payment/gateway/toss")
    @ResponseBody
    public ResponseEntity<Void> selectToss(
            @PathVariable("orderId") long orderId,
            @AuthenticationPrincipal MemberDetails member
    ) {
        selectionService.select(requireMemberId(member), member.getUsername(), orderId, PaymentProvider.TOSS);
        return ResponseEntity.noContent().build();
    }

    private long requireMemberId(MemberDetails member) {
        if (member == null || member.getMemberId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return member.getMemberId();
    }
}
