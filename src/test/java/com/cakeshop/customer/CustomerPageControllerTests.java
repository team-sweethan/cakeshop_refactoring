package com.cakeshop.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.cakeshop.domain.cart.controller.CartController;
import com.cakeshop.domain.cart.dto.view.CartView;
import com.cakeshop.domain.cart.service.CartService;
import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.coupon.service.CouponMemberQueryService;
import com.cakeshop.domain.coupon.dto.view.CustomerCouponView;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.common.paging.PageResult;
import com.cakeshop.domain.coupon.service.CouponOrderQueryService;
import com.cakeshop.domain.home.controller.HomeController;
import com.cakeshop.domain.home.service.HomeService;
import com.cakeshop.domain.member.controller.AuthController;
import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.domain.member.dto.view.MemberProfileView;
import com.cakeshop.domain.member.controller.MyPageController;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.notification.controller.NotificationUserController;
import com.cakeshop.domain.order.controller.customer.OrderController;
import com.cakeshop.domain.order.dto.view.OrderDetailView;
import com.cakeshop.domain.order.dto.view.OrderMemberSummaryView;
import com.cakeshop.domain.order.dto.view.customer.GeneralOrderCheckoutView;
import com.cakeshop.domain.order.service.OrderMemberQueryService;
import com.cakeshop.domain.order.service.PendingPaymentOrderGuideService;
import com.cakeshop.domain.order.service.customer.OrderCheckoutService;
import com.cakeshop.domain.order.service.customer.OrderCustomerService;
import com.cakeshop.domain.order.service.OrderService;
import com.cakeshop.domain.order.service.customer.CustomerCustomOrderService;
import com.cakeshop.domain.payment.controller.PaymentController;
import com.cakeshop.domain.payment.service.PaymentFacade;
import com.cakeshop.domain.payment.service.PaymentCheckoutService;
import com.cakeshop.domain.payment.service.RefundFacade;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.product.controller.ProductController;
import com.cakeshop.domain.product.service.ProductService;
import com.cakeshop.domain.review.service.ReviewProductQueryService;
import com.cakeshop.global.security.MemberDetails;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerPageControllerTests {

    private MockMvc mockMvc;
    private final Map<String, String> pages = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        MemberDetails principal = new MemberDetails(new MemberAuthenticationView(
                1L,
                "customer@cakeshop.local",
                "dummy",
                "CUSTOMER",
                true));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));

        MemberService memberService = mock(MemberService.class);
        when(memberService.getMemberProfile(anyString()))
                .thenReturn(new MemberProfileView(
                        "customer@cakeshop.local",
                        "고객",
                        "케이크러버",
                        "010-1234-5678",
                        LocalDate.of(2000, 1, 15)));

        OrderCustomerService orderQueryService = mock(OrderCustomerService.class);
        when(orderQueryService.getMemberOrders(1L)).thenReturn(List.of());
        when(orderQueryService.getMemberOrder(1L, 1L))
                .thenReturn(mock(OrderDetailView.class));
        CartService cartService = mock(CartService.class);
        when(cartService.getCart(1L)).thenReturn(new CartView(
                List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        CouponOrderQueryService couponOrderQueryService = mock(CouponOrderQueryService.class);
        when(couponOrderQueryService.getAvailableCouponsForMember(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());
        CouponMemberQueryService couponMemberQueryService = mock(CouponMemberQueryService.class);
        when(couponMemberQueryService.getMemberCoupons(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(PageRequest.class)
        )).thenReturn(new PageResult<>(List.<CustomerCouponView>of(), new PageRequest(1, 10), 0));
        OrderMemberQueryService orderMemberQueryService = mock(OrderMemberQueryService.class);
        when(orderMemberQueryService.getMyPageOrders(1L))
                .thenReturn(new OrderMemberSummaryView(List.of(), List.of()));
        OrderCheckoutService orderCheckoutService = mock(OrderCheckoutService.class);
        when(orderCheckoutService.getGeneralCheckout(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyList()
        )).thenReturn(new GeneralOrderCheckoutView(
                1L, "테스트 케이크", "일반 케이크", 1,
                List.of(), BigDecimal.valueOf(30_000), BigDecimal.ZERO,
                BigDecimal.valueOf(30_000), List.of()
        ));

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new HomeController(mock(HomeService.class)),
                        new AuthController(memberService),
                        new ProductController(
                                mock(ProductService.class),
                                mock(ReviewProductQueryService.class)),
                        new CartController(cartService),
                        new OrderController(
                                orderCheckoutService,
                                mock(OrderService.class),
                                orderQueryService,
                                memberService,
                                mock(RefundFacade.class),
                                couponOrderQueryService,
                                mock(CustomerCustomOrderService.class),
                                mock(ProductQueryService.class),
                                mock(CartOrderQueryService.class),
                                mock(PendingPaymentOrderGuideService.class)
                        ),
                        new PaymentController(
                                mock(PaymentFacade.class),
                                mock(PaymentCheckoutService.class)
                        ),
                        new MyPageController(
                                memberService,
                                couponMemberQueryService,
                                orderMemberQueryService,
                                mock(SessionRegistry.class)),
                        new NotificationUserController())
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver())
                .build();

        pages.put("/screens", "home/screens");
        pages.put("/login", "auth/login");
        pages.put("/signup", "customer/member/signup");
        pages.put("/find-email", "customer/member/find-email");
        pages.put("/products", "customer/product/list");
        pages.put("/products/1", "customer/product/detail");
        pages.put("/cart", "customer/cart/list");
        pages.put(
                "/orders/checkout?productId=1&quantity=1&optionIds=1",
                "customer/order/form"
        );
        pages.put("/orders/1/payment", "customer/payment/form");
        pages.put("/orders/complete?orderId=1", "customer/order/complete");
        pages.put("/mypage", "customer/member/mypage");
        pages.put("/orders/1", "customer/order/detail");
        pages.put("/notifications", "customer/notification/list");
        pages.put("/mypage/coupons", "customer/coupon/list");
        pages.put("/mypage/profile", "customer/member/profile-edit");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void everyCustomerRouteReturnsItsTemplate() throws Exception {
        for (Map.Entry<String, String> page : pages.entrySet()) {
            mockMvc.perform(get(page.getKey()))
                    .andExpect(status().isOk())
                    .andExpect(view().name(page.getValue()));
        }
    }

    @Test
    void everyCustomerViewAndSharedAssetExists() {
        pages.values().stream().distinct().forEach(viewName ->
                assertThat(
                        new ClassPathResource("templates/" + viewName + ".html").exists())
                        .as("%s template must exist", viewName)
                        .isTrue()
        );
        assertThat(new ClassPathResource("static/css/app.css").exists()).isTrue();
        assertThat(
                new ClassPathResource("static/js/customer-mockup.js").exists())
                .isTrue();
    }

    @Test
    void socialLoginLinks_requestAccountSelection() throws IOException {
        String loginTemplate = new ClassPathResource("templates/auth/login.html")
                .getContentAsString(StandardCharsets.UTF_8);
        String passwordRecoveryTemplate = new ClassPathResource(
                "templates/customer/member/find-password.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(loginTemplate)
                .contains("@{/oauth2/authorization/google(prompt=select_account)}")
                .contains("@{/oauth2/authorization/kakao(prompt=select_account)}");
        assertThat(passwordRecoveryTemplate)
                .contains("@{/oauth2/authorization/google(prompt=select_account)}")
                .contains("@{/oauth2/authorization/kakao(prompt=select_account)}");
    }

    @Test
    void cartViewUsesServerBackedSpringRoutes()
            throws IOException {
        String cartTemplate =
                new ClassPathResource("templates/customer/cart/list.html")
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(cartTemplate)
                .contains("th:each=\"item : ${cart.items}\"")
                .contains("th:action=\"@{/cart/items/{id}/quantity(id=${item.id})}\"")
                .contains("th:action=\"@{/cart/items/delete-selected}\"")
                .contains("th:action=\"@{/cart/items/delete-all}\"")
                .doesNotContain("data-cart-root");
    }

    @Test
    void customOrderViewUsesServerBackedOptionSubmission() throws IOException {
        String customOrderTemplate =
                new ClassPathResource("templates/customer/order/custom-option.html")
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(customOrderTemplate)
                .contains("th:action=\"@{/orders/custom/request}\"")
                .contains("name=\"productId\"")
                .contains("th:name=\"|customOptionGroup-${group.id}|\"")
                .contains("group.selectionType != 'MULTIPLE'")
                .contains("js-required-multiple")
                .contains("필수 옵션을 하나 이상 선택하세요.")
                .contains("hidden.name = \"optionIds\";")
                .doesNotContain("data-mock-form");
    }

    @Test
    void customRequestViewSubmitsDisplayedOriginalAmount() throws IOException {
        String customRequestTemplate =
                new ClassPathResource("templates/customer/order/custom-request.html")
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(customRequestTemplate)
                .contains("th:field=\"*{displayedOriginalAmount}\"")
                .contains("/js/order-checkout.js")
                .contains("data-discount-type=${coupon.discountType}")
                .contains("data-order-total")
                .contains("data-final-amount");
    }

    @Test
    void orderDetailShowsPaymentButtonForEveryPendingPaymentFlow() throws IOException {
        String orderDetailTemplate =
                new ClassPathResource("templates/customer/order/detail.html")
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(orderDetailTemplate).contains("order.paymentPending");
    }

    @Test
    void pickupViewUsesDedicatedInitializationMarkerAndDisclosesMockData() throws IOException {
        String pickupTemplate =
                new ClassPathResource("templates/customer/order/pickup-setting.html")
                        .getContentAsString(StandardCharsets.UTF_8);
        String mockupScript =
                new ClassPathResource("static/js/customer-mockup.js")
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(pickupTemplate)
                .contains("data-pickup-root")
                .contains("표시된 상품, 가격, 날짜와 시간은 실제 주문 데이터가 아닙니다.");
        assertThat(mockupScript)
                .contains("document.querySelector(\"[data-pickup-root]\")")
                .doesNotContain(
                        "if (!document.querySelector(\"[data-add-normal-cart]\")) return;");
    }

    @Test
    void productDetailSeparatesGeneralCartAndCustomOptionFlows() throws IOException {
        String productDetailTemplate =
                new ClassPathResource("templates/customer/product/detail.html")
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(productDetailTemplate)
                .contains("and product.productType.name() == 'GENERAL'")
                .containsSubsequence(
                        "data-product-option-groups",
                        "th:if=\"${product.productType.name() == 'GENERAL'}\""
                )
                .contains("th:href=\"@{/orders/custom/options(productId=${product.id})}\"")
                .doesNotContain("th:href=\"@{/orders/custom/options}\"");
    }

    @Test
    void cartAsyncUpdateRefreshesEveryAffectedItemAvailability() throws IOException {
        String cartScript = new ClassPathResource("static/js/cart.js")
                .getContentAsString(StandardCharsets.UTF_8);
        String cartTemplate =
                new ClassPathResource("templates/customer/cart/list.html")
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(cartScript)
                .contains("cart.itemAvailability.forEach")
                .contains("state.available");
        assertThat(cartTemplate)
                .contains("item.stockQuantity != null ? item.stockQuantity : 10");
    }

    @Test
    void productDetail_generalOrder_usesIntegratedCheckout() throws IOException {
        String productDetail = new ClassPathResource(
                "templates/customer/product/detail.html"
        ).getContentAsString(StandardCharsets.UTF_8);
        String customerScript = new ClassPathResource(
                "static/js/customer-mockup.js"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(productDetail)
                .contains("th:href=\"@{/orders/checkout}\"")
                .contains("data-order-checkout")
                .doesNotContain("@{/orders/pickup(intent='order')}");
        assertThat(customerScript)
                .contains("new URL(\"/orders/checkout\", location.origin)")
                .contains("url.searchParams.set(\"productId\"")
                .contains("url.searchParams.append(\"optionIds\"");
    }

    @Test
    void productDetail_loginRequiredAction_warnsWithoutStoppingProtectedNavigation()
            throws IOException {
        String productDetail = new ClassPathResource(
                "templates/customer/product/detail.html"
        ).getContentAsString(StandardCharsets.UTF_8);
        String authScript = new ClassPathResource(
                "static/js/product-detail-auth.js"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(productDetail)
                .contains("data-login-required")
                .contains("th:src=\"@{/js/product-detail-auth.js}\"");
        assertThat(authScript)
                .contains("closest(\"[data-login-required]\")")
                .contains("window.alert(\"로그인 후 이용할 수 있습니다.\")")
                .doesNotContain("preventDefault");
    }
}
