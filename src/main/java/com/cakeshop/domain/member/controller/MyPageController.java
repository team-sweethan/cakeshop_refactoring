package com.cakeshop.domain.member.controller;

import com.cakeshop.domain.member.dto.form.ProfileUpdateForm;
import com.cakeshop.domain.member.dto.form.WithdrawForm;
import com.cakeshop.domain.member.dto.view.MemberProfileView;
import com.cakeshop.domain.member.error.MemberErrorCode;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.coupon.service.CouponMemberQueryService;
import com.cakeshop.domain.order.service.OrderMemberQueryService;
import com.cakeshop.domain.review.service.ReviewMemberQueryService;
import com.cakeshop.global.common.paging.PageNavigation;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.security.MemberDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class MyPageController {

    private final MemberService memberService;
    private final CouponMemberQueryService couponMemberQueryService;
    private final OrderMemberQueryService orderMemberQueryService;
    private final ReviewMemberQueryService reviewMemberQueryService;
    private final SessionRegistry sessionRegistry;

    // 마이페이지 조회: 프로필 한 줄, 요약 숫자(진행 중 주문 · 보유 쿠폰 · 작성할 후기), 최근 주문
    @GetMapping("/mypage")
    public String myPage(
            @AuthenticationPrincipal MemberDetails memberDetails,
            Model model) {
        String email = memberDetails.getUsername();
        long memberId = memberDetails.getMemberId();
        model.addAttribute("member", memberService.getMemberProfile(email));
        model.addAttribute(
                "orderSummary",
                orderMemberQueryService.getMyPageOrders(memberId));
        // 보유 쿠폰 수는 보유 쿠폰 화면과 같은 조회(사용 가능 · 기간 안)의 전체 건수다.
        model.addAttribute(
                "availableCouponCount",
                couponMemberQueryService.getMemberCoupons(memberId, new PageRequest(1, 1))
                        .getTotalElements());
        model.addAttribute(
                "writableReviewCount",
                reviewMemberQueryService.countWritableOrderItems(memberId));

        return "customer/member/mypage";
    }

    /** 인증 회원의 보유 쿠폰을 쿠폰 도메인 공개 조회 계약으로 조회한다. */
    @GetMapping("/mypage/coupons")
    public String coupons(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
            Model model) {
        PageRequest pageRequest = new PageRequest(page, 10);
        var coupons = couponMemberQueryService.getMemberCoupons(memberDetails.getMemberId(), pageRequest);
        model.addAttribute("coupons", coupons);
        model.addAttribute("pageNavigation", PageNavigation.of(coupons.getPage(), coupons.getTotalPages()));
        return "customer/coupon/list";
    }

    // 회원정보 수정 화면
    @GetMapping("/mypage/profile")
    public String profile(
            @AuthenticationPrincipal MemberDetails memberDetails,
            Model model) {
        String email = memberDetails.getUsername();
        MemberProfileView member = memberService.getMemberProfile(email);

        model.addAttribute("profileForm", ProfileUpdateForm.from(member));
        model.addAttribute("withdrawForm", new WithdrawForm());
        model.addAttribute("hasPasswordLogin", memberService.hasPasswordLogin(email));

        return "customer/member/profile-edit";
    }


    // 3. 회원정보 수정 처리
    @PostMapping("/mypage/profile/update")
    public String updateProfile(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @Valid @ModelAttribute("profileForm") ProfileUpdateForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (memberDetails == null) {
            return "redirect:/login";
        }

        String email = memberDetails.getUsername();
        if (bindingResult.hasErrors()) {
            // 읽기 전용 이메일은 요청값을 신뢰하지 않고 인증된 회원 정보로 되돌린다.
            form.setEmail(memberService.getMemberProfile(email).email());
            model.addAttribute("withdrawForm", new WithdrawForm());
            model.addAttribute("hasPasswordLogin", memberService.hasPasswordLogin(email));
            return "customer/member/profile-edit";
        }

        // 현재 비밀번호 불일치처럼 화면에서 바로 수정할 수 있는 업무 오류만 필드 오류로 변환한다.
        try {
            memberService.updateMemberInfo(email, form);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != MemberErrorCode.INVALID_CURRENT_PASSWORD) {
                throw exception;
            }
            bindingResult.rejectValue(
                    "currentPassword",
                    MemberErrorCode.INVALID_CURRENT_PASSWORD.code(),
                    MemberErrorCode.INVALID_CURRENT_PASSWORD.message());
            form.setEmail(memberService.getMemberProfile(email).email());
            model.addAttribute("withdrawForm", new WithdrawForm());
            model.addAttribute("hasPasswordLogin", memberService.hasPasswordLogin(email));
            return "customer/member/profile-edit";
        }
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "회원정보가 수정되었습니다.");
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/withdraw")
    public String withdraw(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @Valid @ModelAttribute("withdrawForm") WithdrawForm form,
            BindingResult bindingResult,
            Model model,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        if (memberDetails == null) {
            return "redirect:/login";
        }

        String email = memberDetails.getUsername();
        boolean hasPasswordLogin = memberService.hasPasswordLogin(email);
        boolean missingCurrentPassword = hasPasswordLogin
                && (form.getCurrentPassword() == null || form.getCurrentPassword().isBlank());
        if (missingCurrentPassword) {
            bindingResult.rejectValue(
                    "currentPassword",
                    "NotBlank",
                    "현재 비밀번호를 입력해 주세요.");
        }

        if (bindingResult.hasErrors() || missingCurrentPassword) {
            model.addAttribute(
                    "profileForm",
                    ProfileUpdateForm.from(
                            memberService.getMemberProfile(email)));
            model.addAttribute("hasPasswordLogin", hasPasswordLogin);
            return "customer/member/profile-edit";
        }

        try {
            memberService.withdraw(
                    email,
                    form.getCurrentPassword());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != MemberErrorCode.INVALID_CURRENT_PASSWORD) {
                throw exception;
            }
            bindingResult.rejectValue(
                    "currentPassword",
                    MemberErrorCode.INVALID_CURRENT_PASSWORD.code(),
                    MemberErrorCode.INVALID_CURRENT_PASSWORD.message());
            model.addAttribute(
                    "profileForm",
                    ProfileUpdateForm.from(
                            memberService.getMemberProfile(email)));
            model.addAttribute("hasPasswordLogin", hasPasswordLogin);
            return "customer/member/profile-edit";
        }

        sessionRegistry.getAllSessions(memberDetails, false)
                .forEach(SessionInformation::expireNow);

        // 탈퇴 직후 현재 요청의 세션도 즉시 종료한다.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        redirectAttributes.addFlashAttribute("successMessage", "회원 탈퇴가 완료되었습니다.");
        return "redirect:/login";
    }
}
