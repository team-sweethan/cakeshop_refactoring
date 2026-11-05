package com.cakeshop.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.member.dto.form.ProfileUpdateForm;
import com.cakeshop.domain.member.dto.form.WithdrawForm;
import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.domain.member.dto.view.MemberProfileView;
import com.cakeshop.domain.member.error.MemberErrorCode;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.coupon.service.CouponMemberQueryService;
import com.cakeshop.domain.coupon.dto.view.CustomerCouponView;
import com.cakeshop.domain.coupon.entity.CustomerCouponStatus;
import com.cakeshop.domain.order.dto.view.OrderMemberSummaryView;
import com.cakeshop.domain.order.service.OrderMemberQueryService;
import com.cakeshop.domain.review.service.ReviewMemberQueryService;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.common.paging.PageResult;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.security.MemberDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ExtendWith(MockitoExtension.class)
class MyPageControllerTests {

    @Mock
    private MemberService memberService;

    @Mock
    private CouponMemberQueryService couponMemberQueryService;

    @Mock
    private OrderMemberQueryService orderMemberQueryService;

    @Mock
    private ReviewMemberQueryService reviewMemberQueryService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpSession session;

    @Mock
    private BindingResult bindingResult;

    @Mock
    private SessionRegistry sessionRegistry;

    @Mock
    private SessionInformation currentSessionInformation;

    @Mock
    private SessionInformation otherSessionInformation;

    @Mock
    private Model model;

    @Mock
    private RedirectAttributes redirectAttributes;

    @InjectMocks
    private MyPageController myPageController;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void myPage_authenticatedMember_addsProfileAndOrderSummaryToModel() {
        MemberDetails memberDetails = memberDetails();
        MemberProfileView profile = memberProfile();
        OrderMemberSummaryView orderSummary = new OrderMemberSummaryView(
                java.util.List.of(),
                java.util.List.of());
        when(memberService.getMemberProfile(memberDetails.getUsername()))
                .thenReturn(profile);
        when(orderMemberQueryService.getMyPageOrders(memberDetails.getMemberId()))
                .thenReturn(orderSummary);
        when(couponMemberQueryService.getMemberCoupons(
                org.mockito.ArgumentMatchers.eq(memberDetails.getMemberId()),
                org.mockito.ArgumentMatchers.any(PageRequest.class)))
                .thenReturn(new PageResult<>(java.util.List.of(), new PageRequest(1, 1), 2));
        when(reviewMemberQueryService.countWritableOrderItems(memberDetails.getMemberId()))
                .thenReturn(3L);

        String viewName = myPageController.myPage(memberDetails, model);

        assertThat(viewName).isEqualTo("customer/member/mypage");
        verify(model).addAttribute("member", profile);
        verify(model).addAttribute("orderSummary", orderSummary);
        // 요약 숫자: 보유 쿠폰 수는 보유 쿠폰 조회의 전체 건수, 작성할 후기 수는 후기 계약의 건수
        verify(model).addAttribute("availableCouponCount", 2L);
        verify(model).addAttribute("writableReviewCount", 3L);
    }

    @Test
    void profile_socialOnlyMember_exposesPasswordLoginAvailability() {
        MemberDetails memberDetails = memberDetails();
        MemberProfileView profile = memberProfile();
        when(memberService.getMemberProfile(memberDetails.getUsername()))
                .thenReturn(profile);
        when(memberService.hasPasswordLogin(memberDetails.getUsername()))
                .thenReturn(false);

        String viewName = myPageController.profile(memberDetails, model);

        assertThat(viewName).isEqualTo("customer/member/profile-edit");
        verify(model).addAttribute("hasPasswordLogin", false);
    }

    @Test
    void withdraw_authenticatedMember_invalidatesSessionAndClearsSecurityContext() {
        MemberDetails memberDetails = memberDetails();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        memberDetails,
                        null,
                        memberDetails.getAuthorities()));
        when(request.getSession(false)).thenReturn(session);
        when(sessionRegistry.getAllSessions(memberDetails, false))
                .thenReturn(java.util.List.of(
                        currentSessionInformation,
                        otherSessionInformation));

        String viewName = myPageController.withdraw(
                memberDetails,
                confirmedWithdrawForm(),
                bindingResult,
                model,
                request,
                redirectAttributes);

        assertThat(viewName).isEqualTo("redirect:/login");
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
        verify(memberService).withdraw(
                memberDetails.getUsername(),
                "CurrentPassword1!");
        verify(currentSessionInformation).expireNow();
        verify(otherSessionInformation).expireNow();
        verify(session).invalidate();
        verify(redirectAttributes).addFlashAttribute(
                "successMessage",
                "회원 탈퇴가 완료되었습니다.");
    }

    @Test
    void updateProfile_unauthenticatedMember_redirectsWithoutUpdating() {
        String viewName = myPageController.updateProfile(
                null,
                new ProfileUpdateForm(),
                bindingResult,
                model,
                redirectAttributes);

        assertThat(viewName).isEqualTo("redirect:/login");
        verifyNoInteractions(memberService);
    }

    @Test
    void coupons_authenticatedMember_usesAuthenticatedMemberIdForCouponQuery() {
        MemberDetails memberDetails = memberDetails();
        PageResult<CustomerCouponView> coupons = new PageResult<>(
                java.util.List.of(new CustomerCouponView(
                        10L, "신규 가입 쿠폰", "5,000원 할인", "최소 주문 금액 없음",
                        CustomerCouponStatus.AVAILABLE, java.time.LocalDateTime.now(), java.time.LocalDateTime.now())),
                new PageRequest(1, 10),
                1
        );
        when(couponMemberQueryService.getMemberCoupons(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(PageRequest.class))).thenReturn(coupons);

        String viewName = myPageController.coupons(memberDetails, 1, model);

        assertThat(viewName).isEqualTo("customer/coupon/list");
        verify(couponMemberQueryService).getMemberCoupons(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(PageRequest.class));
        verify(model).addAttribute("coupons", coupons);
    }

    @Test
    void updateProfile_invalidCurrentPassword_returnsFormWithFieldError() {
        MemberDetails memberDetails = memberDetails();
        ProfileUpdateForm form = new ProfileUpdateForm();
        form.setEmail("untrusted@request.local");
        when(bindingResult.hasErrors()).thenReturn(false);
        doThrow(new BusinessException(MemberErrorCode.INVALID_CURRENT_PASSWORD))
                .when(memberService)
                .updateMemberInfo(memberDetails.getUsername(), form);
        when(memberService.getMemberProfile(memberDetails.getUsername()))
                .thenReturn(new MemberProfileView(
                        memberDetails.getUsername(),
                        "회원",
                        "닉네임",
                        "010-1234-5678",
                        LocalDate.of(2000, 1, 15)));

        String viewName = myPageController.updateProfile(
                memberDetails,
                form,
                bindingResult,
                model,
                redirectAttributes);

        assertThat(viewName).isEqualTo("customer/member/profile-edit");
        assertThat(form.getEmail()).isEqualTo(memberDetails.getUsername());
        verify(bindingResult).rejectValue(
                "currentPassword",
                MemberErrorCode.INVALID_CURRENT_PASSWORD.code(),
                MemberErrorCode.INVALID_CURRENT_PASSWORD.message());
    }

    @Test
    void updateProfile_unexpectedBusinessException_delegatesToGlobalHandler() {
        MemberDetails memberDetails = memberDetails();
        ProfileUpdateForm form = new ProfileUpdateForm();
        when(bindingResult.hasErrors()).thenReturn(false);
        BusinessException exception =
                new BusinessException(MemberErrorCode.UPDATE_FAILED);
        doThrow(exception)
                .when(memberService)
                .updateMemberInfo(memberDetails.getUsername(), form);

        assertThatThrownBy(() -> myPageController.updateProfile(
                memberDetails,
                form,
                bindingResult,
                model,
                redirectAttributes))
                .isSameAs(exception);
    }

    @Test
    void updateProfile_authenticatedMember_redirectsWithSuccessMessage() {
        MemberDetails memberDetails = memberDetails();
        ProfileUpdateForm form = new ProfileUpdateForm();

        String viewName = myPageController.updateProfile(
                memberDetails,
                form,
                bindingResult,
                model,
                redirectAttributes);

        assertThat(viewName).isEqualTo("redirect:/mypage");
        verify(memberService).updateMemberInfo(memberDetails.getUsername(), form);
        verify(redirectAttributes).addFlashAttribute(
                "successMessage",
                "회원정보가 수정되었습니다.");
    }

    @Test
    void withdraw_unauthenticatedMember_redirectsWithoutWithdrawing() {
        String viewName = myPageController.withdraw(
                null,
                confirmedWithdrawForm(),
                bindingResult,
                model,
                request,
                redirectAttributes);

        assertThat(viewName).isEqualTo("redirect:/login");
        verifyNoInteractions(memberService, request);
    }

    @Test
    void withdraw_invalidCurrentPassword_rendersProfileWithFieldError() {
        MemberDetails memberDetails = memberDetails();
        WithdrawForm form = confirmedWithdrawForm();
        doThrow(new BusinessException(MemberErrorCode.INVALID_CURRENT_PASSWORD))
                .when(memberService)
                .withdraw(memberDetails.getUsername(), form.getCurrentPassword());
        when(memberService.getMemberProfile(memberDetails.getUsername()))
                .thenReturn(memberProfile());

        String viewName = myPageController.withdraw(
                memberDetails,
                form,
                bindingResult,
                model,
                request,
                redirectAttributes);

        assertThat(viewName).isEqualTo("customer/member/profile-edit");
        verify(bindingResult).rejectValue(
                "currentPassword",
                MemberErrorCode.INVALID_CURRENT_PASSWORD.code(),
                MemberErrorCode.INVALID_CURRENT_PASSWORD.message());
        verify(model).addAttribute(
                org.mockito.ArgumentMatchers.eq("profileForm"),
                org.mockito.ArgumentMatchers.any(ProfileUpdateForm.class));
    }

    @Test
    void withdraw_passwordLoginMember_missingCurrentPassword_rendersFieldError() {
        MemberDetails memberDetails = memberDetails();
        WithdrawForm form = new WithdrawForm();
        form.setWithdrawalConfirmed(true);
        when(memberService.hasPasswordLogin(memberDetails.getUsername())).thenReturn(true);
        when(memberService.getMemberProfile(memberDetails.getUsername())).thenReturn(memberProfile());

        String viewName = myPageController.withdraw(
                memberDetails,
                form,
                bindingResult,
                model,
                request,
                redirectAttributes);

        assertThat(viewName).isEqualTo("customer/member/profile-edit");
        verify(bindingResult).rejectValue(
                "currentPassword",
                "NotBlank",
                "현재 비밀번호를 입력해 주세요.");
        verifyNoInteractions(sessionRegistry, request, redirectAttributes);
    }

    private WithdrawForm confirmedWithdrawForm() {
        WithdrawForm form = new WithdrawForm();
        form.setCurrentPassword("CurrentPassword1!");
        form.setWithdrawalConfirmed(true);
        return form;
    }

    private MemberProfileView memberProfile() {
        return new MemberProfileView(
                "member@cakeshop.local",
                "회원",
                "닉네임",
                "010-1234-5678",
                LocalDate.of(2000, 1, 15));
    }

    private MemberDetails memberDetails() {
        return new MemberDetails(new MemberAuthenticationView(
                1L,
                "member@cakeshop.local",
                "encoded-password",
                "USER",
                true));
    }
}
