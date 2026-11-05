package com.cakeshop.domain.order.controller.admin;

import com.cakeshop.domain.order.infra.DonWorryConsoleProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 관리자 주문 처리 · 주문 상세 화면에 돈워리 오너 "보안 조치 결과" 링크 주소를 넘긴다.
 * "결제 확인 중"인 주문에서 오너가 돈워리에서 진행 상황을 바로 확인하도록 한다. 설정이 없으면 null이라 링크가 숨는다.
 * 설정 빈이 없는 컨텍스트(@WebMvcTest 등)에서도 화면이 뜨도록 ObjectProvider로 받는다.
 */
@ControllerAdvice(assignableTypes = {OrderAdminController.class, FulfillmentAdminController.class})
public class AdminOrderDonWorryLinkAdvice {

    private final ObjectProvider<DonWorryConsoleProperties> donWorryConsoleProperties;

    public AdminOrderDonWorryLinkAdvice(ObjectProvider<DonWorryConsoleProperties> donWorryConsoleProperties) {
        this.donWorryConsoleProperties = donWorryConsoleProperties;
    }

    @ModelAttribute("donWorryOwnerCasesUrl")
    public String donWorryOwnerCasesUrl() {
        DonWorryConsoleProperties properties = donWorryConsoleProperties.getIfAvailable();
        return properties == null ? null : properties.ownerCasesUrl();
    }
}
