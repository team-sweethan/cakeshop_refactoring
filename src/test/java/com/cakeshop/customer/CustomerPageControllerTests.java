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
import com.cakeshop.domain.review.service.ReviewMemberQueryService;
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
                                mock(ReviewMemberQueryService.class),
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

    // 주문서 스크립트는 상품 주문서(달력 · 시 · 분 버튼, 접는 픽업자 칸)와 장바구니 주문서(날짜 선택 상자)를 함께 다룬다.
    // 픽업 일시를 고르지 않아 막힌 제출에서는 결제 버튼을 잠그지 않는다.
    @Test
    void orderCheckoutScript_supportsPickupPickerAndDateSelect() throws IOException {
        String checkoutScript = new ClassPathResource("static/js/order-checkout.js")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(checkoutScript)
                .contains("[data-pickup-date]")
                .contains("[data-pickup-picker]")
                .contains("[data-pickup-calendar]")
                .contains("if (slot) slot.input.checked = true;")
                .contains("if (event.defaultPrevented) return;")
                .contains("showMissingPickup(true);")
                .contains("[data-pickup-submit-error]")
                // 다시 그려도 키보드 포커스가 같은 버튼으로 돌아온다(#48 Codex 리뷰)
                .contains("function keepFocus(draw)")
                .contains("element.dataset.pickerKey = key;")
                .contains("[data-pickup-contact]")
                .contains("pickupContact.hidden = sameAsOrderer.checked && !pickupHasError");
        // 제출 검사가 한 번만 제출 처리보다 먼저 등록돼야 막힌 제출에서 버튼이 잠기지 않는다
        assertThat(checkoutScript.indexOf("initializePickupPicker();"))
                .isLessThan(checkoutScript.indexOf("initializeSingleSubmit();"));
    }

    // 상품 주문서 전용 스타일은 공통 app.css가 아니라 주문서 CSS에 둔다(docs/frontend-template-format.md 6절)
    @Test
    void productCheckoutStyles_liveInOrderCheckoutCss() throws IOException {
        String orderForm = new ClassPathResource("templates/customer/order/form.html")
                .getContentAsString(StandardCharsets.UTF_8);
        String appStyles = new ClassPathResource("static/css/app.css")
                .getContentAsString(StandardCharsets.UTF_8);
        String checkoutStyles = new ClassPathResource("static/css/order-checkout.css")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(orderForm).containsSubsequence(
                "th:href=\"@{/css/app.css}\"",
                "th:href=\"@{/css/order-checkout.css}\"");
        assertThat(appStyles).doesNotContain(".checkout-");
        assertThat(checkoutStyles)
                .contains(".checkout-card {")
                .contains(".checkout-summary {")
                .contains("@container (max-width: 620px)");
    }

    // 마이페이지는 쇼핑 · 활동 · 계정으로 묶은 메뉴와 프로필 한 줄, 요약 숫자 3칸, 최근 주문을 보인다.
    // 회원 정보 표(전화번호 · 생년월일)는 회원정보 수정 화면에서 본다. 화면 전용 스타일은 member-mypage.css에 둔다.
    @Test
    void myPage_groupsMenuAndShowsSummaryCounts() throws IOException {
        String myPage = new ClassPathResource("templates/customer/member/mypage.html")
                .getContentAsString(StandardCharsets.UTF_8);
        String appStyles = new ClassPathResource("static/css/app.css")
                .getContentAsString(StandardCharsets.UTF_8);
        String mypageNav = new ClassPathResource("templates/fragments/customer/mypage-nav.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(mypageNav)
                .containsSubsequence(">마이페이지</a>", ">쇼핑</p>", ">주문 내역</a>", ">보유 쿠폰</a>",
                        ">활동</p>", ">작성할 후기</a>", ">내 후기</a>", ">1:1 문의</a>",
                        ">계정</p>", ">회원정보 수정</a>");
        assertThat(myPage)
                .containsSubsequence(
                        "th:href=\"@{/css/mypage-layout.css}\"",
                        "th:href=\"@{/css/member-mypage.css}\"")
                .contains("mypageNav('home')")
                .contains("<h1 class=\"visually-hidden\">마이페이지</h1>")
                .contains("${orderSummary.inProgressCount}")
                .contains("${availableCouponCount}")
                .contains("${writableReviewCount}")
                .containsSubsequence("진행 중인 주문", "완료된 주문")
                .doesNotContain("member.birthDate")
                .doesNotContain("member.phone")
                .doesNotContain("style=\"");
        assertThat(appStyles).doesNotContain(".mypage-");
    }

    // 1:1 채팅 문의는 두 칸을 화면 높이에 맞춘 고정 높이로 두고 메시지 · 주문 목록을 각자 안에서 스크롤한다.
    // 왼쪽 주문 목록은 진행 중 주문을 위에, 지난 주문은 접어 두고, 긴 주문번호는 카드 안에서 말줄임한다.
    @Test
    void chatRoom_fixedHeightLayoutAndGroupedOrderCards() throws IOException {
        String chatRoom = new ClassPathResource("templates/customer/chat/room.html")
                .getContentAsString(StandardCharsets.UTF_8);
        String chatScript = new ClassPathResource("static/js/chat-customer.js")
                .getContentAsString(StandardCharsets.UTF_8);
        String chatStyles = new ClassPathResource("static/css/chat-room.css")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(chatRoom)
                .contains("th:href=\"@{/css/chat-room.css}\"")
                .contains("class=\"page-container chat-page\"")
                .contains("data-chat-order-count")
                .doesNotContain("max-width:1040px");
        assertThat(chatScript)
                .contains("PAST_ORDER_STATUSES")
                .contains("chat-order-past")
                .contains("case \"READY_FOR_PICKUP\": return \"픽업 준비\";")
                .doesNotContain("픽업 대기");
        assertThat(chatStyles)
                .contains(".chat-page .chat-layout {")
                .contains("grid-template-columns: 260px minmax(0, 1fr)")
                .doesNotContain("max-width: 1040px")
                .contains(".chat-page .chat-messages { flex: 1; max-height: none")
                .contains("text-overflow: ellipsis");
    }

    // 마이페이지 하위 화면(보유 쿠폰 · 작성할 후기 · 내 후기 · 회원정보 수정 · 1:1 문의)도 마이페이지와 같은 왼쪽 메뉴를 쓰고,
    // 지금 화면 메뉴를 강조한다. "마이페이지로 돌아가기" 버튼 대신 메뉴로 오간다.
    @Test
    void mypageSubpages_shareMypageNavWithActiveItem() throws IOException {
        Map<String, String> subpages = Map.of(
                "templates/customer/coupon/list.html", "coupons",
                "templates/customer/review/writable.html", "writable-reviews",
                "templates/customer/review/my.html", "reviews",
                "templates/customer/member/profile-edit.html", "profile",
                "templates/customer/chat/room.html", "chat");

        for (Map.Entry<String, String> subpage : subpages.entrySet()) {
            String template = new ClassPathResource(subpage.getKey())
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(template)
                    .as(subpage.getKey())
                    .contains("th:href=\"@{/css/mypage-layout.css}\"")
                    .contains("mypageNav('" + subpage.getValue() + "')")
                    .contains("class=\"layout-sidebar mypage-layout\"")
                    .doesNotContain("마이페이지로 돌아가기")
                    // 화면 제목은 왼쪽 메뉴 강조가 대신하고, 화면 읽기 도구용 제목만 남긴다.
                    .contains("<h1 class=\"visually-hidden\">");
        }
        assertThat(new ClassPathResource("templates/customer/member/profile-edit.html")
                .getContentAsString(StandardCharsets.UTF_8))
                .contains("th:href=\"@{/css/member-profile.css}\"")
                .contains("class=\"profile-grid\"")
                .contains("id=\"new-password-match-message\"");
    }

    // 결제 완료 화면은 결제가 끝난 것만 알리고 주문 진행은 주문 내역에서 보게 한다.
    // 결제가 보류(돈워리 확인 중)된 주문도 같은 화면이라 상태나 픽업 방문 안내를 넣지 않는다.
    @Test
    void paymentCompletePage_announcesPaymentOnlyAndPointsToOrderHistory() throws IOException {
        String completeTemplate = new ClassPathResource("templates/customer/order/complete.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(completeTemplate)
                .contains("<title>결제 완료 | 스윗한 케이크</title>")
                .contains("결제가 완료되었습니다")
                .contains("마이페이지 &gt; 주문 내역")
                .contains("th:href=\"@{/css/payment-complete.css}\"")
                .contains("th:href=\"@{/orders/{id}(id=${completion.orderId})}\"")
                .doesNotContain("결제와 주문이 완료되었습니다")
                .doesNotContain("주문 완료 |")
                .doesNotContain("확인 중");
    }

    // 결제 수단은 결제 화면에서 바로 고르고(돈워리 추천), 결제하기를 누르면 고른 결제사로 바로 진행한다.
    // 고른 결제사를 저장하는 요청(CSRF 폼)과 돈워리 결제창 모달은 그대로 쓴다. 결제 화면 전용 스타일은 별도 파일이다.
    @Test
    void paymentPage_choosesGatewayOnPageAndShowsRemainingTime() throws IOException {
        String paymentForm = new ClassPathResource("templates/customer/payment/form.html")
                .getContentAsString(StandardCharsets.UTF_8);
        String paymentScript = new ClassPathResource("static/js/payment.js")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(paymentForm)
                .contains("th:href=\"@{/css/payment-checkout.css}\"")
                .contains("name=\"paymentGateway\"")
                .contains("value=\"donworry\"")
                .contains("value=\"toss\"")
                .contains("class=\"payment-method__badge\"")
                .contains("id=\"choose-donworry-form\"")
                .contains("id=\"choose-toss-form\"")
                .contains("data-payment-timer")
                .contains("id=\"payment-modal\"")
                .contains("id=\"donworry-frame\"")
                .doesNotContain("pg-choice");
        assertThat(paymentScript)
                .contains("[data-payment-gateway]:checked")
                .contains("payWithDonWorry()")
                .contains("payWithToss()")
                .contains("event.origin !== checkoutOrigin")
                .contains("target.origin !== location.origin")
                .contains("timer.dataset.expiresAt + \"+09:00\"");
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
                // 옵션 선택 영역은 옵션이 있는 일반 케이크에만 그린다
                .containsSubsequence(
                        "data-product-option-groups",
                        "th:if=\"${product.productType.name() == 'GENERAL' and !optionGroups.isEmpty()}\""
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

    // 평균 별점은 고정 별 다섯 개가 아니라 평균 평점 비율만큼 채운 별로 보여 준다
    @Test
    void productDetail_reviewSummary_fillsStarsByAverageRating() throws IOException {
        String productDetail = new ClassPathResource(
                "templates/customer/product/detail.html"
        ).getContentAsString(StandardCharsets.UTF_8);
        String styles = new ClassPathResource(
                "static/css/app.css"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(productDetail)
                .contains("class=\"rating-stars\"")
                .contains("th:style=\"|--rating: ${product.averageRating}|\"")
                .contains("aria-label=|5점 만점에 ${product.averageRating}점|")
                .contains("<span class=\"rating-stars__fill\" aria-hidden=\"true\">★★★★★</span>");
        assertThat(styles)
                .contains("width: calc(var(--rating, 0) / 5 * 100%)");
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
