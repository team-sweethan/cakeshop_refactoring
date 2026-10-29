package com.cakeshop.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cakeshop.global.config.MariaDbIntegrationTest;
import com.cakeshop.domain.order.dto.form.customer.OrderGeneralCreateForm;
import com.cakeshop.domain.order.service.customer.OrderCheckoutService;
import com.cakeshop.domain.order.service.OrderService;
import com.cakeshop.domain.product.entity.ProductType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.time.LocalDateTime;

// 시드는 Flyway 관리 대상이 아니라서 locations 로는 못 불러온다. 스크립트로 직접 넣는다.
@SpringBootTest(properties = {
    "app.mockup.public-preview=true",
    "app.payment.toss.client-key=test-client-key"
})
@MariaDbIntegrationTest
// encoding 을 명시하지 않으면 JVM 기본 문자셋으로 읽어, 시드의 한글이 PC 에 따라 깨진다.
@Sql(scripts = "classpath:db/seed/seed-local.sql",
     config = @SqlConfig(encoding = "UTF-8"),
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ScreenRenderingTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderCheckoutService orderCheckoutService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long generalProductId;
    private long customProductId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
        generalProductId = jdbcTemplate.queryForObject(
            "SELECT id FROM products WHERE name = '딸기 생크림 케이크 1호'",
            Long.class
        );
        customProductId = jdbcTemplate.queryForObject(
            "SELECT id FROM products WHERE name = '레터링 생크림 케이크'",
            Long.class
        );
    }

    @Test
    @WithUserDetails(value = "user@cakeshop.local", userDetailsServiceBeanName = "memberDetailsService")
    void customerScreensIncludeKoreaTimeFormatter() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+09:00");
        for (String path : new String[]{"/chat", "/notifications"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("/js/korea-time.js")));
        }
    }

    @Test
    @WithUserDetails(value = "admin@cakeshop.local", userDetailsServiceBeanName = "memberDetailsService")
    void adminScreensIncludeKoreaTimeFormatter() throws Exception {
        for (String path : new String[]{"/admin/chat", "/admin/notifications"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("/js/korea-time.js")));
        }
    }

    @Test
    void publicScreensRenderWithoutAuthentication() throws Exception {
        String[] paths = {
            "/screens", "/login", "/admin/login", "/signup", "/find-email",
            "/products", "/products/" + generalProductId
        };

        assertScreensRender(paths);
    }

    @Test
    void productList_doesNotRenderMockNotice() throws Exception {
        mockMvc.perform(get("/products"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("class=\"mock-notice\""))));
    }

    @Test
    void login_successMessage_rendersCommonMessageArea() throws Exception {
        mockMvc.perform(get("/login")
                .flashAttr("successMessage", "회원가입이 완료되었습니다!"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("class=\"flash-stack\"")))
            .andExpect(content().string(containsString("aria-live=\"polite\"")))
            .andExpect(content().string(containsString("회원가입이 완료되었습니다!")))
            .andExpect(content().string(not(containsString("data-common-alert-popup"))))
            .andExpect(content().string(not(containsString("window.alert("))));
    }

    @Test
    void login_footer_rendersPublicStoreInformation() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("케이크 공방")))
            .andExpect(content().string(containsString("서울특별시 강남구 테헤란로 1")))
            .andExpect(content().string(containsString("02-000-0000")))
            .andExpect(content().string(containsString("평일 10:00 ~ 20:00 / 주말 11:00 ~ 21:00")))
            .andExpect(content().string(containsString("매장 1층 픽업 데스크")))
            .andExpect(content().string(not(containsString("매장 정보를 준비 중입니다."))));
    }

    @Test
    void login_authenticationError_rendersFocusableInlineAlert() throws Exception {
        mockMvc.perform(get("/login").param("error", ""))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("이메일 또는 비밀번호가 올바르지 않습니다.")))
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("tabindex=\"-1\"")))
            .andExpect(content().string(containsString("data-login-error")));
    }

    @Test
    void productScreens_errorMessage_renderCommonAlertFragment() throws Exception {
        for (String path : new String[] {
                "/products",
                "/products/" + generalProductId
        }) {
            mockMvc.perform(get(path)
                    .flashAttr("errorMessage", "장바구니 오류"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alert alert--error")))
                .andExpect(content().string(containsString("장바구니 오류")));
        }
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void cart_successMessage_rendersMessageAreaWithoutToastTrigger() throws Exception {
        mockMvc.perform(get("/cart")
                .flashAttr("successMessage", "장바구니를 비웠습니다."))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("class=\"flash-stack\"")))
            .andExpect(content().string(containsString("role=\"status\"")))
            .andExpect(content().string(containsString("aria-live=\"polite\"")))
            .andExpect(content().string(containsString("장바구니를 비웠습니다.")))
            .andExpect(content().string(not(containsString("data-common-alert-popup"))))
            .andExpect(content().string(not(containsString("showToast('장바구니'"))));
    }

    @Test
    void login_recoveredEmail_prefillsEmailInput() throws Exception {
        mockMvc.perform(get("/login")
                .flashAttr("recoveredEmail", "member@example.com"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("value=\"member@example.com\"")));
    }

    @Test
    void myPage_unauthenticatedMember_redirectsToLoginEvenInPublicPreview() throws Exception {
        mockMvc.perform(get("/mypage"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void myPage_successMessage_rendersCommonMessageArea() throws Exception {
        mockMvc.perform(get("/mypage")
                .flashAttr("successMessage", "회원정보가 수정되었습니다."))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("class=\"flash-stack\"")))
            .andExpect(content().string(containsString("aria-live=\"polite\"")))
            .andExpect(content().string(containsString("회원정보가 수정되었습니다.")))
            .andExpect(content().string(not(containsString("data-common-alert-popup"))))
            .andExpect(content().string(not(containsString("window.alert("))))
            .andExpect(content().string(containsString("진행 중인 주문")))
            .andExpect(content().string(containsString("완료된 주문")))
            .andExpect(content().string(containsString("href=\"/orders\"")));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void myPage_generalAndCustomOrders_renderOrderTypeBadges() throws Exception {
        long memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = 'user@cakeshop.local'",
                Long.class);
        long generalProductId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_type = 'GENERAL' ORDER BY id LIMIT 1",
                Long.class);
        long customProductId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_type = 'CUSTOM' ORDER BY id LIMIT 1",
                Long.class);
        insertMyPageOrder(
                memberId, generalProductId, "MYPAGE-GENERAL", "GENERAL",
                "READY_FOR_PICKUP", "일반 배지 케이크");
        insertMyPageOrder(
                memberId, customProductId, "MYPAGE-CUSTOM", "CUSTOM",
                "PICKED_UP", "주문 제작 배지 케이크");

        mockMvc.perform(get("/mypage"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("mypage-order-card__badges")))
            .andExpect(content().string(containsString(">일반 상품</span>")))
            .andExpect(content().string(containsString(">주문 제작</span>")));
    }

    @Test
    void cart_unauthenticatedMember_redirectsToLoginEvenInPublicPreview() throws Exception {
        mockMvc.perform(get("/cart"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    void cartCount_unauthenticatedRequest_isNotSavedForLoginRedirect() throws Exception {
        MvcResult countResult = mockMvc.perform(get("/cart/count"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"))
            .andReturn();

        MockHttpSession countSession = (MockHttpSession) countResult.getRequest().getSession(false);
        assertThat(countSession == null
                ? null
                : countSession.getAttribute("SPRING_SECURITY_SAVED_REQUEST"))
            .isNull();

        MvcResult pageResult = mockMvc.perform(get("/cart"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        MockHttpSession pageSession = (MockHttpSession) pageResult.getRequest().getSession(false);
        assertThat(pageSession).isNotNull();
        assertThat(pageSession.getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNotNull();
    }

    @Test
    void productDetail_unauthenticatedMember_showsLoginCartLinkOnly() throws Exception {
        mockMvc.perform(get("/products/{productId}", generalProductId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("로그인 후 장바구니 담기")))
            .andExpect(content().string(containsString("data-login-required")))
            .andExpect(content().string(containsString("/js/product-detail-auth.js")))
            .andExpect(content().string(matchesPattern(
                "(?s).*href=\"/chat\\?productId="
                    + generalProductId
                    + "\"\\s+data-login-required.*"
            )))
            .andExpect(content().string(containsString("1:1 문의하기")))
            .andExpect(content().string(not(containsString("data-server-cart-form"))));
    }

    @ParameterizedTest
    @EnumSource(ProductType.class)
    void productDetail_breadcrumb_linksToMatchingProductType(
            ProductType productType
    ) throws Exception {
        long productId = productType == ProductType.GENERAL
                ? generalProductId
                : customProductId;

        mockMvc.perform(get("/products/{productId}", productId))
            .andExpect(status().isOk())
            .andExpect(content().string(matchesPattern(
                "(?s).*<a href=\"/products\\?type="
                    + productType.name()
                    + "\"[^>]*>\\s*"
                    + productType.getDisplayName()
                    + "\\s*</a>.*"
            )));
    }

    @Test
    void customProductDetail_unauthenticatedMember_showsLoginRequiredAction() throws Exception {
        mockMvc.perform(get("/products/{productId}", customProductId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("제작 옵션 선택")))
            .andExpect(content().string(containsString("/orders/custom/options")))
            .andExpect(content().string(containsString("data-login-required")));
    }

    @Test
    void productDetailAuthScript_isServed() throws Exception {
        mockMvc.perform(get("/js/product-detail-auth.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("[data-login-required]")));
    }

    @Test
    void productDetail_imagesMissing_rendersPlaceholderWithoutImageTag() throws Exception {
        mockMvc.perform(get("/products/{productId}", generalProductId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("상품 이미지 준비 중")))
            .andExpect(content().string(containsString("등록된 상품 이미지가 없습니다.")))
            .andExpect(content().string(not(containsString("data-product-main-image"))));
    }

    @Test
    @Transactional
    void productDetail_imagesExist_rendersMainImageAndRemainingThumbnails() throws Exception {
        Long productId = jdbcTemplate.queryForObject(
            "SELECT id FROM products WHERE name = '딸기 생크림 케이크 1호'",
            Long.class
        );
        jdbcTemplate.update(
            """
            INSERT INTO product_images (
                product_id,
                image_url,
                sort_order
            )
            VALUES
                (?, '/uploads/product/main.jpg', 0),
                (?, '/uploads/product/second.jpg', 1),
                (?, '/uploads/product/third.jpg', 2)
            """,
            productId,
            productId,
            productId
        );

        mockMvc.perform(get("/products/{productId}", productId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-product-main-image")))
            .andExpect(content().string(containsString("/uploads/product/main.jpg")))
            .andExpect(content().string(containsString("/uploads/product/second.jpg")))
            .andExpect(content().string(containsString("/uploads/product/third.jpg")))
            .andExpect(content().string(not(containsString("등록된 상품 이미지가 없습니다."))));
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void memberScreensRenderWithSeededUser() throws Exception {
        String[] paths = {
            "/cart",
            "/orders/checkout?productId=" + generalProductId + "&quantity=1",
            "/orders/custom/options?productId=" + customProductId,
            "/mypage",
            "/orders", "/notifications", "/mypage/reviews/writable", "/mypage/coupons",
            "/mypage/profile"
        };

        assertScreensRender(paths);

        mockMvc.perform(get("/products/{productId}", generalProductId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-server-cart-form")))
            .andExpect(content().string(containsString(
                "href=\"/chat?productId=" + generalProductId + "\""
            )))
            .andExpect(content().string(containsString("1:1 문의하기")))
            .andExpect(content().string(not(containsString("data-login-required"))))
            .andExpect(content().string(not(containsString("로그인 후 장바구니 담기"))));
    }

    @Test
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void commonHeader_adminAccount_rendersOnlyAdminAccountMenu() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString(">관리자</a>"))))
            .andExpect(content().string(not(containsString("href=\"/mypage\""))))
            .andExpect(content().string(not(containsString("data-cart-count"))))
            .andExpect(content().string(not(containsString("href=\"/notifications\""))))
            .andExpect(content().string(containsString("관리자 계정 ·")));

        mockMvc.perform(get("/products/{productId}", generalProductId))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("주문서 작성하기"))))
            .andExpect(content().string(not(containsString("data-server-cart-form"))))
            .andExpect(content().string(not(containsString("data-product-option-groups"))))
            .andExpect(content().string(not(containsString("data-quantity"))))
            .andExpect(content().string(not(containsString("data-product-price-summary"))))
            .andExpect(content().string(not(containsString("href=\"/mypage/reviews/writable\""))));

        mockMvc.perform(get("/community"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("href=\"/community/new\""))));
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void commonHeader_customerAccount_rendersCustomerAccountMenu() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/mypage\"")))
            .andExpect(content().string(containsString("data-account-identity")))
            .andExpect(content().string(containsString("data-cart-count")))
            .andExpect(content().string(not(containsString(">관리자</a>"))));
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void myPage_linksToOrderListWithoutMockOrders() throws Exception {
        mockMvc.perform(get("/mypage"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/orders\"")))
            .andExpect(content().string(not(containsString("/orders/1"))))
            .andExpect(content().string(not(containsString("ORD-001"))))
            .andExpect(content().string(not(containsString("ORD-004"))));
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void memberScreens_doNotRenderMockNotice() throws Exception {
        for (String path : new String[] {"/signup", "/mypage", "/mypage/profile"}) {
            mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("class=\"mock-notice\""))));
        }
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void generalProductDetail_showsQuantityControlsInPriceSummary() throws Exception {
        mockMvc.perform(get("/products/{productId}", generalProductId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-quantity")))
            .andExpect(content().string(containsString("data-selected-quantity")))
            .andExpect(content().string(containsString("data-product-price-summary")));
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void customProductDetail_showsCustomOptionFlowWithoutServerCartForm() throws Exception {
        mockMvc.perform(get("/products/{productId}", customProductId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/orders/custom/options")))
            .andExpect(content().string(containsString("data-product-price-summary")))
            .andExpect(content().string(not(containsString("data-login-required"))))
            .andExpect(content().string(not(containsString("data-server-cart-form"))))
            .andExpect(content().string(not(containsString("data-quantity"))))
            .andExpect(content().string(not(containsString("data-selected-quantity"))));
    }

    @Test
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void orderCheckout_rendersActualProductAndGeneralOrderAction()
            throws Exception {
        mockMvc.perform(get(
                "/orders/checkout?productId="
                    + generalProductId
                    + "&quantity=1"
            ))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("딸기 생크림 케이크 1호")))
            .andExpect(content().string(containsString("action=\"/orders/general\"")))
            .andExpect(content().string(containsString("data-pickup-date")))
            .andExpect(content().string(containsString("data-pickup-time-panel")))
            .andExpect(content().string(containsString("type=\"radio\"")))
            .andExpect(content().string(containsString("data-same-as-orderer")))
            .andExpect(content().string(containsString("value=\"테스트회원\"")))
            .andExpect(content().string(containsString("value=\"010-0000-0002\"")))
            .andExpect(content().string(not(containsString("data-success-url"))))
            .andExpect(content().string(not(containsString("/orders/1/payment"))));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void paymentScreen_createdOrder_rendersActualTossRequestData()
            throws Exception {
        long orderId = createGeneralOrder();

        mockMvc.perform(get("/orders/{orderId}/payment", orderId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("딸기 생크림 케이크")))
            .andExpect(content().string(containsString(
                "https://js.tosspayments.com/v2/standard"
            )))
            .andExpect(content().string(containsString("data-payment-amount=\"35000\"")))
            .andExpect(content().string(not(containsString("data-mock-form"))));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void orderDetail_createdOrder_rendersStableHistoryCardLayout()
            throws Exception {
        long orderId = createGeneralOrder();

        mockMvc.perform(get("/orders/{orderId}", orderId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("order-history-card")))
            .andExpect(content().string(containsString(
                "order-history-card__number"
            )))
            .andExpect(content().string(containsString("order-history-detail")))
            .andExpect(content().string(not(containsString(
                "style=\"grid-column:span 2\""
            ))));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void completionScreen_donePayment_rendersActualOrderAndMethod()
            throws Exception {
        long orderId = createGeneralOrder();
        jdbcTemplate.update(
            "UPDATE payments SET status = 'DONE', payment_key = ?, method = ? "
                + "WHERE order_id = ?",
            "test-payment-key-" + orderId,
            "카드",
            orderId
        );
        jdbcTemplate.update(
            "UPDATE orders SET status = 'READY_FOR_PICKUP' WHERE id = ?",
            orderId
        );

        mockMvc.perform(get("/orders/complete").param("orderId", String.valueOf(orderId)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("딸기 생크림 케이크")))
            .andExpect(content().string(containsString("35,000원")))
            .andExpect(content().string(containsString("카드")))
            .andExpect(content().string(containsString("매장 1층 픽업 데스크")));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "user@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void paymentCallbacks_createdOrder_renderCsrfBridgeAndSafeFailure()
            throws Exception {
        long orderId = createGeneralOrder();
        String gatewayOrderId = jdbcTemplate.queryForObject(
            "SELECT toss_order_id FROM payments WHERE order_id = ?",
            String.class,
            orderId
        );

        mockMvc.perform(get("/orders/{orderId}/payment/success", orderId)
                .param("paymentKey", "test-payment-key")
                .param("orderId", gatewayOrderId)
                .param("amount", "35000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("payment-confirm-form")))
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andExpect(content().string(containsString(
                "/orders/" + orderId + "/payment/confirm"
            )));

        mockMvc.perform(get("/orders/{orderId}/payment/fail", orderId)
                .param("code", "PAY_PROCESS_CANCELED")
                .param("message", "provider raw message must not be shown"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("결제가 취소되었습니다")))
            .andExpect(content().string(not(containsString(
                "provider raw message must not be shown"
            ))));
    }

    @Test
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void productOptionAdminScreenRendersWithSeededAdmin()
            throws Exception {
        assertScreensRender(new String[] {"/admin/products"});

        mockMvc.perform(get(
                "/admin/products/{productId}/options",
                customProductId
            ))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("옵션:")))
            .andExpect(content().string(containsString("1호")))
            .andExpect(content().string(containsString("2호")))
            .andExpect(content().string(containsString("위로 이동")))
            .andExpect(content().string(containsString("아래로 이동")))
            .andExpect(content().string(not(
                containsString("name=\"sortOrder\"")
            )));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void fulfillmentAdminScreen_readyOrder_marksPickupComplete()
            throws Exception {
        long orderId = createGeneralOrder();
        jdbcTemplate.update(
            """
            UPDATE payments
            SET status = 'DONE',
                payment_key = ?,
                approved_at = CURRENT_TIMESTAMP(6)
            WHERE order_id = ?
            """,
            "SCREEN-FULFILLMENT-" + orderId,
            orderId
        );
        jdbcTemplate.update(
            """
            UPDATE orders
            SET status = 'READY_FOR_PICKUP',
                ready_at = CURRENT_TIMESTAMP(6),
                pickup_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
            WHERE id = ?
            """,
            orderId
        );
        LocalDateTime pickupAt = jdbcTemplate.queryForObject(
            "SELECT pickup_at FROM orders WHERE id = ?",
            LocalDateTime.class,
            orderId
        );

        mockMvc.perform(get("/admin/fulfillment"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("주문 처리")))
            .andExpect(content().string(containsString("검토 대기")))
            .andExpect(content().string(containsString("모든 단계의 주문을 기간 제한 없이 확인합니다.")))
            .andExpect(content().string(not(containsString("name=\"pickupDate\""))))
            .andExpect(content().string(containsString(
                pickupAt.format(java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm"))
            )))
            .andExpect(content().string(containsString(
                "딸기 생크림 케이크 1호 × 1"
            )))
            .andExpect(content().string(containsString("기본")))
            .andExpect(content().string(containsString("<details")))
            .andExpect(content().string(containsString(
                "data-order-id=\"" + orderId + "\""
            )))
            .andExpect(content().string(containsString("테스트회원")))
            .andExpect(content().string(containsString("주문 ID #" + orderId)))
            .andExpect(content().string(containsString("픽업 완료 처리")))
            .andExpect(content().string(containsString(
                "/admin/fulfillment/" + orderId + "/pickup"
            )))
            .andExpect(content().string(containsString("name=\"_csrf\"")));

        mockMvc.perform(post("/admin/fulfillment/{orderId}/pickup", orderId)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/fulfillment"));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM orders WHERE id = ?",
            String.class,
            orderId
        )).isEqualTo("PICKED_UP");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT picked_up_by FROM orders WHERE id = ?",
            Long.class,
            orderId
        )).isEqualTo(jdbcTemplate.queryForObject(
            "SELECT id FROM members WHERE email = ?",
            Long.class,
            "admin@cakeshop.local"
        ));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void fulfillmentAdminScreen_customOrder_rendersRequestMessageAndLettering()
            throws Exception {
        long orderId = createGeneralOrder();
        jdbcTemplate.update(
            "UPDATE orders SET order_type = 'CUSTOM', status = 'UNDER_REVIEW', request_message = ? WHERE id = ?",
            "문구는 짧게\n초는 하늘색으로",
            orderId
        );
        jdbcTemplate.update(
            "UPDATE order_items SET requirements = ? WHERE order_id = ?",
            "생일 축하해\n사랑해",
            orderId
        );

        mockMvc.perform(get("/admin/fulfillment"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("제작 요청사항")))
            .andExpect(content().string(containsString("문구는 짧게\n초는 하늘색으로")))
            .andExpect(content().string(containsString("레터링 문구")))
            .andExpect(content().string(containsString("생일 축하해\n사랑해")))
            .andExpect(content().string(containsString("style=\"white-space:pre-wrap\"")));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void paymentAdminScreen_donePayment_rendersActualPaymentHistory()
            throws Exception {
        long orderId = createGeneralOrder();
        jdbcTemplate.update(
            """
            UPDATE payments
            SET status = 'DONE',
                payment_key = ?,
                method = 'CARD',
                approved_at = CURRENT_TIMESTAMP(6)
            WHERE order_id = ?
            """,
            "SCREEN-PAYMENT-" + orderId,
            orderId
        );

        String orderNumber = jdbcTemplate.queryForObject(
            "SELECT order_number FROM orders WHERE id = ?",
            String.class,
            orderId
        );

        mockMvc.perform(get("/admin/payments").param("status", "DONE"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("결제 기록")))
            .andExpect(content().string(containsString(orderNumber)))
            .andExpect(content().string(containsString("35,000원")))
            .andExpect(content().string(containsString("결제 완료")))
            .andExpect(content().string(containsString("data-payment-id")))
            .andExpect(content().string(containsString(
                "/admin/orders/" + orderId
            )))
            .andExpect(content().string(containsString("전체 주문 보기")))
            .andExpect(content().string(not(containsString("PAY-001"))))
            .andExpect(content().string(not(containsString("환불 처리하시겠습니까"))));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void paymentAdminScreen_expiredPayment_rendersPersistentCheckForm()
            throws Exception {
        long orderId = createGeneralOrder();
        long paymentId = jdbcTemplate.queryForObject(
                "SELECT id FROM payments WHERE order_id = ?",
                Long.class,
                orderId
        );
        jdbcTemplate.update(
                "UPDATE payments SET status = 'EXPIRED' WHERE id = ?",
                paymentId
        );

        mockMvc.perform(get("/admin/payments").param("status", "EXPIRED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "/admin/payments/" + paymentId + "/expiration-check"
                )))
                .andExpect(content().string(containsString("name=\"checked\"")))
                .andExpect(content().string(containsString("만료 확인")));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void adminOrderList_rendersOrderTypeAndWorkspaceGuidance() throws Exception {
        long customerId = jdbcTemplate.queryForObject(
            "SELECT id FROM members WHERE email = ?",
            Long.class,
            "user@cakeshop.local"
        );
        createGeneralOrder();

        mockMvc.perform(get("/admin/orders"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("전체 주문")))
            .andExpect(content().string(containsString("유형")))
            .andExpect(content().string(containsString("일반 상품")))
            .andExpect(content().string(containsString(
                "검토·제작·픽업 업무는 주문 처리에서 진행합니다."
            )))
            .andExpect(content().string(containsString(
                "/admin/chat?customerId=" + customerId
            )))
            .andExpect(content().string(containsString("채팅하기")));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void adminOrderDetail_generalPaidOrder_rendersCancellationForm()
            throws Exception {
        long orderId = createGeneralOrder();
        jdbcTemplate.update(
            """
            UPDATE payments
            SET status = 'DONE',
                payment_key = ?,
                method = 'CARD',
                approved_at = CURRENT_TIMESTAMP(6)
            WHERE order_id = ?
            """,
            "SCREEN-ADMIN-CANCEL-" + orderId,
            orderId
        );
        jdbcTemplate.update(
            """
            UPDATE orders
            SET status = 'READY_FOR_PICKUP',
                discount_amount = 5000,
                final_amount = original_amount - 5000,
                ready_at = CURRENT_TIMESTAMP(6)
            WHERE id = ?
            """,
            orderId
        );

        mockMvc.perform(get("/admin/orders/{orderId}", orderId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("관리자 취소 사유")))
            .andExpect(content().string(containsString("주문·결제 취소")))
            .andExpect(content().string(containsString("주문 처리에서 열기")))
            .andExpect(content().string(containsString(
                "/admin/fulfillment?status=READY_FOR_PICKUP&amp;page=1"
            )))
            .andExpect(content().string(not(containsString(
                "/admin/orders/" + orderId + "/pickup"
            ))))
            .andExpect(content().string(containsString(
                "/admin/orders/" + orderId + "/cancel"
            )))
            .andExpect(content().string(containsString("쿠폰 적용")))
            .andExpect(content().string(containsString("적용")))
            .andExpect(content().string(containsString("쿠폰 할인 금액")))
            .andExpect(content().string(containsString("-5,000원")))
            .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void adminOrderDetail_customOrder_rendersEscapedRequestMessageWithLineBreaks()
            throws Exception {
        long orderId = createGeneralOrder();
        String requestMessage = "문구는 짧게\n초는 하늘색으로 <script>alert('xss')</script>";
        jdbcTemplate.update(
            "UPDATE orders SET order_type = 'CUSTOM', request_message = ? WHERE id = ?",
            requestMessage,
            orderId
        );
        jdbcTemplate.update(
            "UPDATE order_items SET requirements = ? WHERE order_id = ?",
            "생일 축하해\n사랑해",
            orderId
        );

        mockMvc.perform(get("/admin/orders/{orderId}", orderId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("제작 요청사항")))
            .andExpect(content().string(containsString("문구는 짧게\n초는 하늘색으로")))
            .andExpect(content().string(containsString("레터링 문구")))
            .andExpect(content().string(containsString("생일 축하해\n사랑해")))
            .andExpect(content().string(containsString("style=\"white-space:pre-wrap\"")))
            .andExpect(content().string(containsString("&lt;script&gt;")))
            .andExpect(content().string(not(containsString("<script>alert('xss')</script>"))));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void adminOrderDetail_customOrderWithoutRequestMessage_rendersEmptyState()
            throws Exception {
        long orderId = createGeneralOrder();
        jdbcTemplate.update(
            "UPDATE orders SET order_type = 'CUSTOM', request_message = NULL WHERE id = ?",
            orderId
        );
        jdbcTemplate.update(
            "UPDATE order_items SET requirements = NULL WHERE order_id = ?",
            orderId
        );

        mockMvc.perform(get("/admin/orders/{orderId}", orderId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("제작 요청사항")))
            .andExpect(content().string(containsString("입력된 제작 요청사항이 없습니다.")))
            .andExpect(content().string(containsString("레터링 문구")))
            .andExpect(content().string(containsString("입력된 레터링 문구가 없습니다.")));
    }

    @Test
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void productEdit_imagesMissing_rendersUploadFormAndPlaceholder()
            throws Exception {
        mockMvc.perform(get(
                "/admin/products/{productId}/edit",
                generalProductId
            ))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "data-product-image-upload"
            )))
            .andExpect(content().string(containsString(
                "등록된 상품 이미지가 없습니다."
            )))
            .andExpect(content().string(containsString(
                "form=\"productInfoForm\""
            )))
            .andExpect(content().string(not(containsString(
                "data-product-image-delete"
            ))))
            .andExpect(content().string(not(containsString(
                "data-product-image-replace"
            ))))
            .andExpect(content().string(not(matchesPattern(
                "(?s).*<button(?=[^>]*data-image-upload-button)"
                    + "(?=[^>]*disabled)[^>]*>.*"
            ))));
    }

    @Test
    @Transactional
    @WithUserDetails(
        value = "admin@cakeshop.local",
        userDetailsServiceBeanName = "memberDetailsService"
    )
    void productEdit_fiveImagesExist_rendersRepresentativeAndDisablesUpload()
            throws Exception {
        Long productId = jdbcTemplate.queryForObject(
            "SELECT id FROM products WHERE name = '딸기 생크림 케이크 1호'",
            Long.class
        );
        jdbcTemplate.update(
            """
            INSERT INTO product_images (
                product_id,
                image_url,
                sort_order
            )
            VALUES
                (?, '/uploads/product/first.jpg', 0),
                (?, '/uploads/product/second.jpg', 1),
                (?, '/uploads/product/third.jpg', 2),
                (?, '/uploads/product/fourth.jpg', 3),
                (?, '/uploads/product/fifth.jpg', 4)
            """,
            productId,
            productId,
            productId,
            productId,
            productId
        );
        Long firstImageId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM product_images
            WHERE product_id = ?
              AND image_url = '/uploads/product/first.jpg'
            """,
            Long.class,
            productId
        );

        mockMvc.perform(get(
                "/admin/products/{productId}/edit",
                productId
            ))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "/uploads/product/first.jpg"
            )))
            .andExpect(content().string(containsString(
                "대표 이미지"
            )))
            .andExpect(content().string(containsString(
                "product-image-card"
            )))
            .andExpect(content().string(containsString(
                "product-image-badge--placeholder"
            )))
            .andExpect(content().string(containsString(
                "product-image-actions"
            )))
            .andExpect(content().string(containsString(
                "data-product-image-delete"
            )))
            .andExpect(content().string(containsString(
                "data-product-image-replace"
            )))
            .andExpect(content().string(containsString(
                "form=\"productImageUploadForm\""
            )))
            .andExpect(content().string(containsString(
                "/admin/products/"
                    + productId
                    + "/images/"
                    + firstImageId
                    + "/replace"
            )))
            .andExpect(content().string(containsString(
                "multipart/form-data"
            )))
            .andExpect(content().string(containsString(
                "이 상품 이미지를 교체하시겠습니까?"
            )))
            .andExpect(content().string(not(containsString(
                "교체할 이미지"
            ))))
            .andExpect(content().string(containsString(
                "/admin/products/"
                    + productId
                    + "/images/"
                    + firstImageId
                    + "/delete"
            )))
            .andExpect(content().string(containsString(
                "이 상품 이미지를 삭제하시겠습니까?"
            )))
            .andExpect(content().string(containsString(
                "상품 이미지를 최대 5장까지 등록했습니다."
            )))
            .andExpect(content().string(matchesPattern(
                "(?s).*<button(?=[^>]*data-image-upload-button)"
                    + "(?=[^>]*disabled)[^>]*>.*"
            )))
            .andExpect(content().string(not(matchesPattern(
                "(?s).*<input(?=[^>]*id=\"imageFile\")"
                    + "(?=[^>]*disabled)[^>]*>.*"
            ))));
    }

    private void insertMyPageOrder(
            long memberId,
            long productId,
            String orderNumber,
            String orderType,
            String status,
            String productName
    ) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 15, 10, 0);
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    order_number, member_id, orderer_name, orderer_phone,
                    pickup_name, pickup_phone, original_amount, discount_amount,
                    final_amount, status, pickup_at, created_at, updated_at, order_type
                )
                VALUES (?, ?, '테스트회원', '010-0000-0002', '테스트회원', '010-0000-0002',
                        35000, 0, 35000, ?, ?, ?, ?, ?)
                """,
                orderNumber,
                memberId,
                status,
                createdAt.plusDays(1),
                createdAt,
                createdAt,
                orderType
        );
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?",
                Long.class,
                orderNumber
        );
        jdbcTemplate.update(
                """
                INSERT INTO order_items (
                    order_id, product_id, product_name, product_type, quantity,
                    base_price, option_amount, total_amount, preparation_days
                )
                VALUES (?, ?, ?, ?, 1, 35000, 0, 35000, 0)
                """,
                orderId,
                productId,
                productName,
                orderType
        );
    }

    private void assertScreensRender(String[] paths) throws Exception {
        for (String path : paths) {
            mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        }
    }

    private long createGeneralOrder() {
        long memberId = jdbcTemplate.queryForObject(
            "SELECT id FROM members WHERE email = ?",
            Long.class,
            "user@cakeshop.local"
        );
        var checkout = orderCheckoutService.getGeneralCheckout(
            generalProductId,
            1,
            List.of()
        );

        OrderGeneralCreateForm form = new OrderGeneralCreateForm();
        form.setRequestKey(java.util.UUID.randomUUID().toString());
        form.setProductId(generalProductId);
        form.setQuantity(1);
        form.setOptionIds(List.of());
        form.setOrdererName("테스트회원");
        form.setOrdererPhone("010-0000-0002");
        form.setPickupName("테스트회원");
        form.setPickupPhone("010-0000-0002");
        form.setPickupAt(
            checkout.pickupDates().getFirst().times().getFirst().value()
        );

        return orderService.createGeneralOrder(memberId, form).orderId();
    }
}
