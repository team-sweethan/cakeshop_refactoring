package com.cakeshop.domain.order.controller.admin;

import com.cakeshop.domain.order.dto.form.OrderCancelForm;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.service.admin.AdminOrderService;
import com.cakeshop.domain.order.service.admin.FulfillmentService;
import com.cakeshop.domain.payment.service.RefundFacade;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class OrderAdminController {

    private final AdminOrderService orderAdminService;
    private final FulfillmentService fulfillmentService;
    private final RefundFacade refundFacade;

    /** 주문 목록 리스트 가져오기**/
    @GetMapping("/admin/orders")
    public String orders(Model model) {
        model.addAttribute("orders", orderAdminService.getOrders());
        return "admin/order/list";
    }

    /** 주문 목록 중 한건 한건에 대한 정보 가져오기.**/
    @GetMapping("/admin/orders/{orderId}")
    public String detail(@PathVariable("orderId") long orderId, Model model) {
        var order = orderAdminService.getOrder(orderId);
        model.addAttribute("order", order);
        // PG(돈워리)가 결제를 확인 중이면 "결제 확인 중"을 함께 보인다 (DonWorry ADR-0064 4절)
        model.addAttribute(
                "paymentUnderReview",
                order.status() != OrderStatus.PICKED_UP && fulfillmentService.isPaymentUnderGatewayReview(orderId)
        );
        if (order.isFulfillmentManageable()) {
            model.addAttribute(
                    "fulfillmentPage",
                    fulfillmentService.getFulfillmentPage(order.orderId(), order.status())
            );
        }
        // 주문 처리 목록과 같은 처리 버튼(제작 시작 · 픽업 준비 · 반려 · 제작 완료 · 픽업 완료)을 상세에도 보인다.
        // 처리 단계가 아닌 주문이면 null이라 버튼이 없다.
        model.addAttribute(
                "fulfillmentOrder",
                fulfillmentService.getFulfillmentOrder(orderId).orElse(null)
        );
        return "admin/order/detail";
    }

    /** 관리자 취소 로직. **/
    @PostMapping("/admin/orders/{orderId}/cancel")
    public String cancel(
            @PathVariable("orderId") long orderId,
            @AuthenticationPrincipal MemberDetails admin,
            @Valid @ModelAttribute OrderCancelForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (admin == null || admin.getMemberId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        refundFacade.cancelAdminOrder(admin.getMemberId(), orderId, form.getReason());
        redirectAttributes.addFlashAttribute("successMessage", "주문과 결제를 취소했습니다.");
        return "redirect:/admin/orders/" + orderId;
    }
}
