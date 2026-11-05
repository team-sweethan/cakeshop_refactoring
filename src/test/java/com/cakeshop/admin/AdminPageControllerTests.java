package com.cakeshop.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cakeshop.domain.coupon.dto.form.CouponSearchCondition;
import com.cakeshop.domain.coupon.dto.view.CouponDisplayStatus;
import com.cakeshop.domain.coupon.dto.view.CouponUpdateView;
import com.cakeshop.domain.coupon.dto.view.CouponView;
import com.cakeshop.domain.coupon.entity.CouponTargetType;
import com.cakeshop.domain.coupon.entity.DiscountType;
import com.cakeshop.domain.product.service.ProductAdminService;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.common.paging.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cakeshop.domain.community.controller.CommunityAdminController;
import com.cakeshop.domain.community.dto.view.AdminPostDetailView;
import com.cakeshop.domain.community.dto.view.AdminPostListView;
import com.cakeshop.domain.community.dto.view.CommentSectionView;
import com.cakeshop.domain.community.entity.PostStatus;
import com.cakeshop.domain.community.service.CommunityAdminService;
import com.cakeshop.domain.community.service.CommunityCommentService;
import com.cakeshop.domain.community.service.CommunityPostImageService;
import com.cakeshop.domain.coupon.controller.CouponAdminController;
import com.cakeshop.domain.coupon.service.CouponAdminService;
import com.cakeshop.domain.member.controller.MemberAdminController;
import com.cakeshop.domain.member.service.MemberAdminService;
import com.cakeshop.domain.member.service.MemberSessionService;
import com.cakeshop.domain.order.service.OrderMemberQueryService;
import com.cakeshop.domain.notification.controller.NotificationAdminController;
import com.cakeshop.domain.order.controller.admin.FulfillmentAdminController;
import com.cakeshop.domain.order.controller.admin.OrderAdminController;
import com.cakeshop.domain.order.dto.view.OrderDetailView;
import com.cakeshop.domain.order.dto.view.admin.FulfillmentListView;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.service.admin.AdminOrderService;
import com.cakeshop.domain.order.service.admin.AdminCustomOrderService;
import com.cakeshop.domain.order.service.admin.FulfillmentService;
import com.cakeshop.domain.payment.controller.PaymentAdminController;
import com.cakeshop.domain.payment.dto.view.PaymentAdminListView;
import com.cakeshop.domain.payment.dto.view.PaymentAdminSummaryView;
import com.cakeshop.domain.payment.service.PaymentAdminService;
import com.cakeshop.domain.payment.service.RefundFacade;
import com.cakeshop.domain.product.controller.ProductAdminController;
import com.cakeshop.domain.review.controller.ReviewAdminController;
import com.cakeshop.domain.review.dto.view.AdminReviewDetailView;
import com.cakeshop.domain.review.dto.view.AdminReviewListView;
import com.cakeshop.domain.review.entity.ReviewStatus;
import com.cakeshop.domain.review.service.ReviewAdminService;
import com.cakeshop.domain.statistics.controller.StatisticsAdminController;
import com.cakeshop.domain.dashboard.controller.DashboardAdminController;
import com.cakeshop.domain.statistics.dto.view.PeriodStatisticsView;
import com.cakeshop.domain.dashboard.dto.view.DashboardView;
import com.cakeshop.domain.dashboard.service.DashboardReadModelQueryService;
import com.cakeshop.domain.statistics.service.query.AdditionalMetricsReadModelQueryService;
import com.cakeshop.domain.statistics.service.query.PeriodStatisticsReadModelQueryService;
import com.cakeshop.domain.statistics.service.query.ProductPeriodStatisticsReadModelQueryService;

class AdminPageControllerTests {

    private MockMvc mockMvc;

    private final Map<String, String> pages = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {

        CouponAdminService couponAdminService =
                Mockito.mock(CouponAdminService.class);

        // 관리자 공통 페이지 테스트에서 목록 컨트롤러가 페이징 값을 계산할 수 있도록 빈 결과를 반환한다.
        when(couponAdminService.getCoupons(
                any(CouponSearchCondition.class),
                any(PageRequest.class)
        )).thenReturn(new PageResult<CouponView>(
                List.of(),
                new PageRequest(null, null),
                0
        ));
        when(couponAdminService.getUpdateView(1L)).thenReturn(new CouponUpdateView(
                "테스트 쿠폰", DiscountType.FIXED_AMOUNT, BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 10L,
                LocalDateTime.of(2026, 8, 1, 9, 0),
                LocalDateTime.of(2026, 8, 31, 23, 59),
                CouponTargetType.SPECIFIC_MEMBERS, CouponDisplayStatus.ACTIVE, true
        ));

        FulfillmentService fulfillmentService =
                Mockito.mock(FulfillmentService.class);
        when(fulfillmentService.getFulfillments(any(), any()))
                .thenReturn(new FulfillmentListView(
                        null,
                        new PageResult<>(List.of(), new PageRequest(null, null), 0)
                ));

        AdminOrderService adminOrderService = Mockito.mock(AdminOrderService.class);
        when(adminOrderService.getOrder(1L)).thenReturn(new OrderDetailView(
                1L, "ORD-1", OrderType.GENERAL, OrderStatus.PENDING_PAYMENT,
                "주문자", "010-1111-1111", "수령자", "010-2222-2222",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDateTime.of(2026, 8, 1, 10, 0), null, false,
                null, LocalDateTime.of(2026, 8, 1, 9, 0), false, List.of()
        ));

        PaymentAdminService paymentAdminQueryService =
                Mockito.mock(PaymentAdminService.class);
        when(paymentAdminQueryService.getPayments(any()))
                .thenReturn(new PaymentAdminListView(
                        null,
                        new PaymentAdminSummaryView(0, 0, 0, 0),
                        List.of()
                ));

        // 커뮤니티 관리 화면은 조각 5에서 목업을 걷어내고 실제 데이터를 그린다.
        // 이 테스트는 "주소가 그 템플릿을 가리키는가"만 보므로 빈 결과로 충분하다.
        CommunityAdminService communityAdminService =
                Mockito.mock(CommunityAdminService.class);
        CommunityCommentService communityCommentService = Mockito.mock(CommunityCommentService.class);
        DashboardReadModelQueryService dashboardReadModelQueryService =
                Mockito.mock(DashboardReadModelQueryService.class);
        PeriodStatisticsReadModelQueryService periodStatisticsReadModelQueryService =
                Mockito.mock(PeriodStatisticsReadModelQueryService.class);
        ProductPeriodStatisticsReadModelQueryService productStatisticsQueryService =
                Mockito.mock(ProductPeriodStatisticsReadModelQueryService.class);
        AdditionalMetricsReadModelQueryService additionalMetricsQueryService =
                Mockito.mock(AdditionalMetricsReadModelQueryService.class);

        ReviewAdminService reviewAdminService = Mockito.mock(ReviewAdminService.class);
        when(reviewAdminService.getReviews(any(), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageResult<AdminReviewListView>(
                        List.of(), new PageRequest(null, null), 0));
        when(reviewAdminService.getReviewDetail(anyLong()))
                .thenReturn(new AdminReviewDetailView(
                        1L, 9L, "작성자", "상품명", "20260101-0001", 5, 5, 4, 4, "본문",
                        LocalDateTime.now(), LocalDateTime.now(), ReviewStatus.PUBLISHED,
                        List.of(), null));

        when(communityAdminService.getPosts(any(), any(), any(PageRequest.class)))
                .thenReturn(new PageResult<AdminPostListView>(
                        List.of(), new PageRequest(null, null), 0));
        when(communityAdminService.getPostDetail(anyLong()))
                .thenReturn(new AdminPostDetailView(
                        15L, 1L, "후기", "제목", "본문", "작성자", false,
                        PostStatus.PUBLISHED, null, null, null, 0, 0,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(communityAdminService.getReports(anyLong())).thenReturn(List.of());
        when(communityCommentService.getComments(anyLong(), any(), any()))
                .thenReturn(new CommentSectionView(List.of(), 0, 0, 20));
        when(dashboardReadModelQueryService.getDashboard())
                .thenReturn(new DashboardView(
                        LocalDateTime.of(2026, 8, 17, 12, 0),
                        0L,
                        BigDecimal.ZERO,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        List.of(),
                        List.of(),
                        List.of()
                ));
        when(periodStatisticsReadModelQueryService.getStatistics(any()))
                .thenReturn(new PeriodStatisticsView(
                        LocalDate.of(2026, 7, 28),
                        LocalDate.of(2026, 8, 3),
                        0L,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        List.of()
                ));

        mockMvc = MockMvcBuilders.standaloneSetup(
                new DashboardAdminController(dashboardReadModelQueryService),
                new StatisticsAdminController(
                        periodStatisticsReadModelQueryService,
                        productStatisticsQueryService,
                        additionalMetricsQueryService
                ),
                new ProductAdminController(
                        Mockito.mock(ProductAdminService.class)),
                new OrderAdminController(
                        adminOrderService,
                        Mockito.mock(FulfillmentService.class),
                        Mockito.mock(RefundFacade.class)),
                new FulfillmentAdminController(
                        fulfillmentService,
                        Mockito.mock(AdminCustomOrderService.class),
                        Mockito.mock(RefundFacade.class)),
                new PaymentAdminController(paymentAdminQueryService),
                new MemberAdminController(
                        Mockito.mock(MemberAdminService.class),
                        Mockito.mock(MemberSessionService.class),
                        Mockito.mock(OrderMemberQueryService.class)),
                new ReviewAdminController(reviewAdminService),
                new NotificationAdminController(),
                new CommunityAdminController(communityAdminService, communityCommentService, mock(CommunityPostImageService.class)),
                new CouponAdminController(couponAdminService)
        ).build();

        pages.put("/admin", "admin/dashboard");
        pages.put("/admin/statistics", "admin/statistics");
        pages.put("/admin/products", "admin/product/list");
        pages.put("/admin/products/new", "admin/product/form");
        pages.put("/admin/products/1/edit", "admin/product/form");
        pages.put("/admin/orders", "admin/order/list");
        pages.put("/admin/orders/1", "admin/order/detail");
        pages.put("/admin/fulfillment", "admin/fulfillment/list");
        pages.put("/admin/payments", "admin/payment/list");
        pages.put("/admin/members", "admin/member/list");
        pages.put("/admin/reviews", "admin/review/list");
        pages.put("/admin/reviews/1", "admin/review/detail");
        pages.put("/admin/notifications", "admin/notification/list");
        pages.put("/admin/community", "admin/community/list");
        pages.put("/admin/community/15", "admin/community/detail");
        pages.put("/admin/coupons", "admin/coupon/list");
        pages.put("/admin/coupons/create", "admin/coupon/form");
        pages.put("/admin/coupons/1/edit", "admin/coupon/form");
        pages.put("/admin/coupons/1/detail", "admin/coupon/detail");
    }

    @Test
    void everyAdminRouteReturnsItsTemplate() throws Exception {
        for (Map.Entry<String, String> page : pages.entrySet()) {
            mockMvc.perform(get(page.getKey()))
                .andExpect(status().isOk())
                .andExpect(view().name(page.getValue()));
        }
    }

    @Test
    void everyAdminViewHasAThymeleafTemplate() {
        pages.values().stream().distinct().forEach(viewName ->
            assertThat(new ClassPathResource("templates/" + viewName + ".html").exists())
                .as("%s 템플릿이 존재해야 한다", viewName)
                .isTrue()
        );
    }

    // 관리자 주문 화면 세 곳(전체 주문 · 주문 상세 · 주문 처리)은 같은 전용 CSS와 상태 색을 쓴다.
    // 전체 주문은 상태 탭 7개(전체 · 결제 대기 · 주문 확인 중 · 제작 중 · 픽업 준비 · 픽업 완료 · 취소·반려·만료)로 거른다.
    // 주문 상세는 맨 위 요약 · 정보 카드 3개 · 접힌 관리자 취소, 주문 처리는 펼친 내용을 경고 · 정보 · 제작 요청 상자로 나눈다.
    @Test
    void adminOrderScreens_statusTabsSummaryAndColoredSections() throws java.io.IOException {
        String list = new ClassPathResource("templates/admin/order/list.html")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String detail = new ClassPathResource("templates/admin/order/detail.html")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String fulfillment = new ClassPathResource("templates/admin/fulfillment/list.html")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String script = new ClassPathResource("static/js/admin-order-list.js")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String appStyles = new ClassPathResource("static/css/app.css")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        for (String template : java.util.List.of(list, detail, fulfillment)) {
            assertThat(template).contains("th:href=\"@{/css/admin-order.css}\"");
        }
        assertThat(list)
                .containsSubsequence(
                        "data-order-filter=\"all\"",
                        "data-order-filter=\"PENDING_PAYMENT\"",
                        "data-order-filter=\"UNDER_REVIEW\"",
                        "data-order-filter=\"IN_PRODUCTION\"",
                        "data-order-filter=\"READY_FOR_PICKUP\"",
                        "data-order-filter=\"PICKED_UP\"",
                        "data-order-filter=\"CLOSED\"")
                .contains("data-order-status-group")
                .contains("badge--muted");
        assertThat(script).contains("row.dataset.orderStatusGroup === filter");
        assertThat(detail)
                .contains("admin-order-summary")
                .contains("admin-order-cards")
                .contains("<details class=\"admin-order-cancel\"")
                .contains("name=\"returnTo\" value=\"detail\"")
                .contains("th:action=\"@{/admin/fulfillment/{id}/pickup(id=${f.orderId})}\"")
                .contains("&lt; 주문 처리")
                .doesNotContain("쿠폰 적용");
        assertThat(fulfillment)
                .contains("class=\"admin-order-alert\"")
                .contains("class=\"fulfillment-info\"")
                .contains("class=\"admin-order-request\"")
                .contains("주문 상세 보기")
                .doesNotContain("전체 주문 보기");
        assertThat(appStyles).doesNotContain(".admin-order-");
    }
}
