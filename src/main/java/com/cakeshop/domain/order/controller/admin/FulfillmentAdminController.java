package com.cakeshop.domain.order.controller.admin;

import com.cakeshop.domain.order.dto.form.admin.FulfillmentSearchCondition;
import com.cakeshop.domain.order.dto.form.OrderCancelForm;
import com.cakeshop.domain.order.dto.view.admin.FulfillmentListView;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.service.admin.AdminCustomOrderService;
import com.cakeshop.domain.order.service.admin.FulfillmentService;
import com.cakeshop.domain.payment.service.RefundFacade;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import com.cakeshop.global.security.MemberDetails;
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
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

import java.util.Set;

@Controller
@RequiredArgsConstructor
public class FulfillmentAdminController {

    private static final Set<OrderStatus> FULFILLMENT_STATUSES = Set.of(
            OrderStatus.UNDER_REVIEW,
            OrderStatus.IN_PRODUCTION,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.PICKED_UP
    );

    // PG 결제 확인 중 진행에 대한 확인 창 동의 (화면 hidden 값)
    static final String REVIEW_ACKNOWLEDGED = "reviewAcknowledged";

    private final FulfillmentService fulfillmentService;
    private final AdminCustomOrderService adminCustomOrderService;
    private final RefundFacade refundFacade;

    @GetMapping("/admin/fulfillment")
    public String fulfillment(
            @ModelAttribute("condition") FulfillmentSearchCondition condition,
            BindingResult bindingResult,
            @RequestParam(required = false) String page,
            Model model
    ) {
        // 검증
        recoverInvalidSearchValues(condition, bindingResult);
        FulfillmentListView fulfillment = fulfillmentService.getFulfillments(
                condition,
                new PageRequest(parsePositiveInteger(page), FulfillmentService.PAGE_SIZE)
        );
        condition.setStatus(fulfillment.selectedStatus());
        model.addAttribute("fulfillment", fulfillment);
        return "admin/fulfillment/list";
    }

    /**
     * 픽업 대기 -> 픽업 완료.
     * reviewAcknowledged: PG(돈워리)가 결제를 확인 중일 때 화면 확인 창에 동의했는지. 확인 중이면 동의가 있어야 진행된다.
     */
    @PostMapping("/admin/fulfillment/{orderId}/pickup")
    public String markPickedUp(
            @PathVariable("orderId") long orderId,
            @ModelAttribute FulfillmentSearchCondition condition,
            @AuthenticationPrincipal MemberDetails admin,
            @RequestParam(name = REVIEW_ACKNOWLEDGED, defaultValue = "false") boolean reviewAcknowledged,
            RedirectAttributes redirectAttributes
    ) {
        if (admin == null || admin.getMemberId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        fulfillmentService.markPickedUp(orderId, admin.getMemberId(), reviewAcknowledged);

        redirectAttributes.addFlashAttribute("successMessage", "픽업 완료로 변경했습니다.");
        return "redirect:" + fulfillmentRedirectUrl(condition);
    }

    /** PG(돈워리)가 결제를 확인 중인 일반 주문을 고객 확인 뒤 픽업 준비로 넘긴다 (DonWorry ADR-0064 4절). */
    @PostMapping("/admin/fulfillment/{orderId}/ready")
    public String moveToReadyForPickup(
            @PathVariable("orderId") long orderId,
            @ModelAttribute FulfillmentSearchCondition condition,
            @AuthenticationPrincipal MemberDetails admin,
            @RequestParam(name = REVIEW_ACKNOWLEDGED, defaultValue = "false") boolean reviewAcknowledged,
            RedirectAttributes redirectAttributes
    ) {
        fulfillmentService.moveToReadyForPickup(orderId, requireAdminMemberId(admin), reviewAcknowledged);
        redirectAttributes.addFlashAttribute("successMessage", "픽업 준비로 변경했습니다.");
        return "redirect:" + fulfillmentRedirectUrl(condition);
    }

    /** 승인 대기 수제 주문의 제작을 시작한다. */
    @PostMapping("/admin/fulfillment/{orderId}/production/start")
    public String startProduction(
            @PathVariable("orderId") long orderId,
            @ModelAttribute FulfillmentSearchCondition condition,
            @AuthenticationPrincipal MemberDetails admin,
            @RequestParam(name = REVIEW_ACKNOWLEDGED, defaultValue = "false") boolean reviewAcknowledged,
            RedirectAttributes redirectAttributes
    ) {
        adminCustomOrderService.startProduction(orderId, requireAdminMemberId(admin), reviewAcknowledged);
        redirectAttributes.addFlashAttribute("successMessage", "제작을 시작했습니다.");
        return "redirect:" + fulfillmentRedirectUrl(condition);
    }

    /** 제작 중 수제 주문을 제작 완료 후 픽업 대기로 변경한다. */
    @PostMapping("/admin/fulfillment/{orderId}/production/complete")
    public String completeProduction(
            @PathVariable("orderId") long orderId,
            @ModelAttribute FulfillmentSearchCondition condition,
            @AuthenticationPrincipal MemberDetails admin,
            @RequestParam(name = REVIEW_ACKNOWLEDGED, defaultValue = "false") boolean reviewAcknowledged,
            RedirectAttributes redirectAttributes
    ) {
        adminCustomOrderService.completeProduction(orderId, requireAdminMemberId(admin), reviewAcknowledged);
        redirectAttributes.addFlashAttribute("successMessage", "제작 완료 후 픽업 대기로 변경했습니다.");
        return "redirect:" + fulfillmentRedirectUrl(condition);
    }

    /** 승인 전 수제 주문을 반려하고 전액 환불한다. */
    @PostMapping("/admin/fulfillment/{orderId}/reject")
    public String reject(
            @PathVariable("orderId") long orderId,
            @ModelAttribute FulfillmentSearchCondition condition,
            @AuthenticationPrincipal MemberDetails admin,
            @Valid @ModelAttribute OrderCancelForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        refundFacade.rejectCustomOrder(requireAdminMemberId(admin), orderId, form.getReason());
        redirectAttributes.addFlashAttribute("successMessage", "주문을 반려하고 결제를 환불했습니다.");
        return "redirect:" + fulfillmentRedirectUrl(condition);
    }

    /** 지원하지 않는 작업 단계 검색값을 기본값으로 복구한다. */
    private void recoverInvalidSearchValues(
            FulfillmentSearchCondition condition,
            BindingResult bindingResult
    ) {
        if (bindingResult.hasFieldErrors("status")) {
            condition.setStatus(null);
        }
    }

    private String fulfillmentRedirectUrl(FulfillmentSearchCondition condition) {
        UriComponentsBuilder redirect = UriComponentsBuilder.fromPath("/admin/fulfillment");
        if (condition != null
                && condition.getStatus() != null
                && FULFILLMENT_STATUSES.contains(condition.getStatus())) {
            redirect.queryParam("status", condition.getStatus());
        }
        return redirect.toUriString();
    }

    private Integer parsePositiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long requireAdminMemberId(MemberDetails admin) {
        if (admin == null || admin.getMemberId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return admin.getMemberId();
    }
}
