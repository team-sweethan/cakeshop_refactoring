package com.cakeshop.domain.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cakeshop.domain.community.dto.view.CommentSectionView;
import com.cakeshop.domain.community.entity.CommentStatus;
import com.cakeshop.domain.community.entity.NoticeStatus;
import com.cakeshop.domain.community.entity.PostStatus;
import com.cakeshop.domain.member.dto.view.MemberAuthenticationView;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import com.cakeshop.global.security.MemberDetails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 커뮤니티 화면을 실제 Thymeleaf로 렌더링한다.
 *
 * <p>이 클래스가 소유하는 것은 네 가지다. 템플릿별 대표 렌더링(템플릿명과 Model 연결), 사용자 입력
 * escaping, 화면 결과가 실질적으로 달라지는 상태, 그리고 커뮤니티 고유의 인가 거절과 그 뒤의 DB 상태다.
 *
 * <p>업무 규칙은 다른 테스트가 소유한다. 상태별 노출·인기글 판정은 {@code CommunityPostServiceTests},
 * 댓글 상한 계산은 {@code CommunityCommentServiceTests}, Model 계약과 요청 파라미터 처리는
 * {@code CommunityControllerTests}, 조회수는
 * {@code CommunityViewCountTests}, 탈퇴 회원 마스킹은 {@code CommunityMemberContractTests},
 * SQL 결과는 {@code CommunityMapperTests}가 본다. docs/testing.md 8절에 따라 권한별 버튼, 안내 문구,
 * 집계 포맷과 HTML 조각은 여기에서 고정하지 않는다. 다만 페이지 링크의 조건 보존과 댓글 상한 도달
 * 안내처럼 템플릿에서만 확인할 수 있는 계약은 대표 상태로 검증한다.
 */
@SpringBootTest
@MariaDbIntegrationTest
@Transactional
class CommunityScreenRenderingTests {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 3, 1, 10, 0);

    /** 인기글 화면 검사용 확정일. */
    private static final LocalDate RANKING_DATE = LocalDate.of(2099, 1, 2);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long categoryId;
    private long emptyCategoryId;
    private long memberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String suffix = Long.toString(System.nanoTime());

        categoryId = insertCategory("SCREEN_" + suffix, "화면테스트");
        emptyCategoryId = insertCategory("SCREEN_EMPTY_" + suffix, "빈카테고리");
        memberId = insertMember("screen-" + suffix + "@cakeshop.local", "케이크덕후", "ACTIVE");
    }

    @Test
    void communityList_rendersPostRow() throws Exception {
        insertPost(memberId, "딸기 케이크 후기", "본문", PostStatus.PUBLISHED);

        mockMvc.perform(get("/community").param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("딸기 케이크 후기")))
                .andExpect(content().string(containsString("케이크덕후")))
                .andExpect(content().string(containsString("화면테스트")));
    }

    @Test
    void communityList_withoutPosts_rendersEmptyMessage() throws Exception {
        mockMvc.perform(get("/community").param("categoryId", String.valueOf(emptyCategoryId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("아직 등록된 글이 없습니다.")));
    }

    /** 카테고리 탭과 정렬을 한 줄 도구 줄에 그리고, 고른 탭·정렬에만 aria-current 를 단다. */
    @Test
    void communityList_rendersCategoryTabsAndSortInOneToolbar() throws Exception {
        MvcResult result = mockMvc.perform(get("/community")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("sort", "VIEWS"))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();

        assertThat(html)
                .contains("<div class=\"community-toolbar section\">")
                .contains("<nav class=\"community-tabs\" aria-label=\"카테고리\">")
                .contains("<nav class=\"community-sort\" aria-label=\"정렬\">")
                .contains("/css/community.css");
        assertThat(html.split("aria-current=\"page\"", -1)).hasSize(2);
        assertThat(html.split("aria-current=\"true\"", -1)).hasSize(2);
        assertThat(html.replaceAll("\\s+", " "))
                .contains("community-tabs__item is-active\" aria-current=\"page\" href=\"/community?categoryId="
                        + categoryId)
                .contains("community-sort__item is-active\" aria-current=\"true\" href=\"/community?categoryId="
                        + categoryId + "&amp;keyword=&amp;sort=VIEWS\"");
    }

    /** 페이지를 이동해도 현재 카테고리와 정렬 조건을 함께 유지한다. */
    @Test
    void communityList_paginationLink_preservesCategoryAndSort() throws Exception {
        for (int i = 0; i < PageRequest.DEFAULT_SIZE + 1; i++) {
            insertPost(memberId, "페이지 글 " + i, "본문", PostStatus.PUBLISHED);
        }

        mockMvc.perform(get("/community")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("sort", "VIEWS"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "/community?categoryId=" + categoryId
                                + "&amp;keyword=&amp;sort=VIEWS&amp;page=2")));
    }

    /** 쪽을 옮겨도 검색어를 잃지 않는다. 잃으면 검색이 풀리는데 화면은 정상으로 보인다. */
    @Test
    void communityList_paginationLink_preservesKeyword() throws Exception {
        for (int i = 0; i < PageRequest.DEFAULT_SIZE + 1; i++) {
            insertPost(memberId, "케이크 " + i, "본문", PostStatus.PUBLISHED);
        }

        mockMvc.perform(get("/community")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("keyword", "케이크"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("keyword=%EC%BC%80%EC%9D%B4%ED%81%AC")));
    }

    /** 검색칸에 방금 친 말이 남아 있어야 다음 조건을 이어 걸 수 있다. */
    @Test
    void communityList_searching_keepsKeywordInSearchBox() throws Exception {
        insertPost(memberId, "딸기 케이크 후기", "본문", PostStatus.PUBLISHED);

        mockMvc.perform(get("/community").param("keyword", "딸기"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"딸기\"")))
                .andExpect(content().string(containsString("딸기 케이크 후기")));
    }

    /** 검색 결과가 없을 때와 글이 아예 없을 때는 다른 문구다. */
    @Test
    void communityList_searchWithNoMatch_rendersSearchEmptyMessage() throws Exception {
        insertPost(memberId, "초코 타르트", "본문", PostStatus.PUBLISHED);

        mockMvc.perform(get("/community").param("keyword", "없는낱말"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("로 찾은 글이 없습니다")))
                .andExpect(content().string(not(containsString("아직 등록된 글이 없습니다."))));
    }

    /** 검색 중에는 인기글 사이드바를 그리지 않는다. */
    @Test
    void communityList_searching_hidesPopularSection() throws Exception {
        long postId = insertPost(memberId, "이번 주 인기 케이크", "본문", PostStatus.PUBLISHED);
        insertRanking(1, postId);
        insertBatchRun(1);

        // 검색어가 없으면 그대로 그린다.
        mockMvc.perform(get("/community"))
                .andExpect(content().string(containsString("<h2>인기글</h2>")));

        mockMvc.perform(get("/community").param("keyword", "케이크"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<h2>인기글</h2>"))))
                .andExpect(content().string(containsString("이번 주 인기 케이크")));
    }

    /** 확정된 인기글이 있으면 목록 위에 별도 영역을 렌더링한다. */
    @Test
    void communityList_withConfirmedRanking_rendersPopularSection() throws Exception {
        long postId = insertPost(memberId, "이번 주 인기 케이크", "본문", PostStatus.PUBLISHED);
        insertRanking(1, postId);
        insertBatchRun(1);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("인기글")))
                .andExpect(content().string(containsString("이번 주 인기 케이크")));
    }

    /** 작성 화면에는 활성 카테고리만 채운다. */
    @Test
    void communityCreateForm_rendersActiveCategories() throws Exception {
        insertInactiveCategory();

        mockMvc.perform(get("/community/new").with(authentication(authorOf(memberId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("화면테스트")))
                .andExpect(content().string(not(containsString("비활성분류"))));
    }

    /** 수정 화면에 기존 값을 채운다. */
    @Test
    void communityEditForm_author_rendersExistingValues() throws Exception {
        long postId = insertPost(memberId, "고칠 제목", "고칠 본문", PostStatus.PUBLISHED);

        mockMvc.perform(get("/community/" + postId + "/edit")
                        .with(authentication(authorOf(memberId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("고칠 제목")))
                .andExpect(content().string(containsString("고칠 본문")));
    }

    /** 저장 경로가 있는 작성 화면은 비로그인에게 열지 않는다(SecurityConfig의 커뮤니티 고유 규칙). */
    @Test
    void communityCreateForm_anonymous_isSentToLogin() throws Exception {
        mockMvc.perform(get("/community/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void communityDetail_rendersContent() throws Exception {
        long postId = insertPost(memberId, "제목입니다", "첫 줄\n둘째 줄", PostStatus.PUBLISHED);

        mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("제목입니다")))
                .andExpect(content().string(containsString("첫 줄")));
    }

    @Test
    void communityDetail_attachedImages_renderBelowContentInSortOrder() throws Exception {
        long postId = insertPost(memberId, "첨부 있는 글", "본문", PostStatus.PUBLISHED);
        insertPostImage(postId, "/uploads/community/202608/second.jpg", 1);
        insertPostImage(postId, "/uploads/community/202608/first.jpg", 0);

        String body = mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body.indexOf("본문")).isLessThan(body.indexOf("first.jpg"));
        assertThat(body.indexOf("first.jpg")).isLessThan(body.indexOf("second.jpg"));
    }

    @Test
    void communityEditForm_existingImages_renderWithDeleteChoice() throws Exception {
        long postId = insertPost(memberId, "첨부 있는 글", "본문", PostStatus.PUBLISHED);
        insertPostImage(postId, "/uploads/community/202608/first.jpg", 0);

        mockMvc.perform(get("/community/" + postId + "/edit")
                        .with(authentication(authorOf(memberId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("multipart/form-data")))
                .andExpect(content().string(containsString("first.jpg")))
                .andExpect(content().string(containsString("deleteImageIds")));
    }

    /** 관리자에게 열린 것은 댓글뿐이다. 좋아요·신고는 화면에서도 없다(H50). */
    @Test
    void communityDetail_adminAccount_rendersCommentFormWithoutLikeAndReport() throws Exception {
        long postId = insertPost(memberId, "관리자 조회용 글", "본문", PostStatus.PUBLISHED);

        mockMvc.perform(get("/community/" + postId).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 조회용 글")))
                .andExpect(content().string(containsString("/comments")))
                .andExpect(content().string(containsString("댓글 작성")))
                .andExpect(content().string(not(containsString("/likes"))))
                .andExpect(content().string(not(containsString("/reports"))))
                .andExpect(content().string(not(containsString("로그인하면 댓글을 쓸 수 있습니다."))));
    }

    /** 관리자 댓글은 Security를 통과해 실제로 저장된다(H50). */
    @Test
    void communityComment_adminAccount_isAcceptedAndStored() throws Exception {
        long postId = insertPost(memberId, "질문 글", "본문", PostStatus.PUBLISHED);
        long adminId = insertAdminMember();

        MvcResult result = mockMvc.perform(post("/community/" + postId + "/comments")
                        .param("content", "관리자 답변입니다.")
                        .with(csrf())
                        .with(authentication(authenticationOf(adminId, "ADMIN"))))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(commentCountOf(postId)).isEqualTo(1);

        // 방금 쓴 댓글 그 줄로 돌아간다. 새 댓글은 목록 맨 아래라 맨 위 착지에서 가장 멀다.
        Long commentId = jdbcTemplate.queryForObject(
                "SELECT id FROM comments WHERE content = '관리자 답변입니다.'", Long.class);
        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("/community/" + postId + "#comment-" + commentId);
    }

    /** 관리자도 자기 댓글은 지운다 — 작성자 본인 규칙 그대로다(H50). */
    @Test
    void communityCommentDelete_adminOwnComment_isAcceptedAndSoftDeleted() throws Exception {
        long postId = insertPost(memberId, "질문 글", "본문", PostStatus.PUBLISHED);
        long adminId = insertAdminMember();
        long commentId =
                insertComment(postId, adminId, "지울 답변", CommentStatus.PUBLISHED, BASE_TIME);

        mockMvc.perform(post("/community/" + postId + "/comments/" + commentId + "/delete")
                        .with(csrf())
                        .with(authentication(authenticationOf(adminId, "ADMIN"))))
                .andExpect(status().is3xxRedirection());

        assertThat(commentStatusOf(commentId)).isEqualTo(CommentStatus.DELETED.name());
    }

    /** 열지 않은 경계가 열리지 않았다 — 좋아요는 인기 점수라 관리자에게 닫힌 채다(H50). */
    @Test
    void communityLike_adminAccount_isForbiddenAndWritesNothing() throws Exception {
        long postId = insertPost(memberId, "좋아요 대상 글", "본문", PostStatus.PUBLISHED);
        long adminId = insertAdminMember();

        mockMvc.perform(post("/community/" + postId + "/likes")
                        .with(csrf())
                        .with(authentication(authenticationOf(adminId, "ADMIN"))))
                .andExpect(status().isForbidden());

        assertThat(likeCountOf(postId)).isZero();
    }

    /** 신고도 닫힌 채다 — 차단 권한자가 자기에게 보고할 일은 없다(H50). */
    @Test
    void communityReport_adminAccount_isForbiddenAndWritesNothing() throws Exception {
        long postId = insertPost(memberId, "신고 대상 글", "본문", PostStatus.PUBLISHED);
        long adminId = insertAdminMember();

        mockMvc.perform(post("/community/" + postId + "/reports")
                        .param("reason", "신고 사유")
                        .with(csrf())
                        .with(authentication(authenticationOf(adminId, "ADMIN"))))
                .andExpect(status().isForbidden());

        assertThat(reportCountOf(postId)).isZero();
    }

    @Test
    void communityDetail_htmlInTitleContentAndComment_isEscaped() throws Exception {
        String contentAttack = "<script>alert('xss')</script>";
        String commentAttack = "<script>alert('comment')</script>";
        long postId = insertPost(
                memberId, "<img src=x onerror=alert(1)>", contentAttack, PostStatus.PUBLISHED);
        insertComment(postId, memberId, commentAttack, CommentStatus.PUBLISHED, BASE_TIME);

        mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<img src=x"))))
                .andExpect(content().string(not(containsString(contentAttack))))
                .andExpect(content().string(not(containsString(commentAttack))))
                .andExpect(content().string(containsString("&lt;img src=x")))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    /** 차단 글 작성자에게는 본문 대신 사유와 안내를 보여 주고 수정 경로를 끊는다. */
    @Test
    void communityDetail_blockedPostAuthor_showsReasonAndHidesActions() throws Exception {
        long postId = insertPost(memberId, "차단된 글", "본문", PostStatus.BLOCKED);
        jdbcTemplate.update(
                "UPDATE posts SET blocked_reason = ? WHERE id = ?", "광고성 게시물", postId);

        mockMvc.perform(get("/community/" + postId).with(authentication(authorOf(memberId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자가 차단한 게시글입니다.")))
                .andExpect(content().string(containsString("광고성 게시물")))
                .andExpect(content().string(
                        not(containsString("/community/" + postId + "/edit"))));
    }

    /** 작성자가 아닌 조회자에게는 차단 사유가 응답 본문에 남지 않는다. */
    @Test
    void communityDetail_blockedPost_anonymousViewer_returnsNotFound() throws Exception {
        long postId = insertPost(memberId, "차단된 글", "본문", PostStatus.BLOCKED);
        jdbcTemplate.update(
                "UPDATE posts SET blocked_reason = ? WHERE id = ?", "광고성 게시물", postId);

        mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("광고성 게시물"))));
    }

    /** 로그인 회원에게는 서버로 전송되는 댓글 폼을 렌더링한다. */
    @Test
    void communityDetail_authenticated_showsCommentForm() throws Exception {
        long postId = insertPost(memberId, "글", "본문", PostStatus.PUBLISHED);

        mockMvc.perform(get("/community/" + postId).with(authentication(authorOf(memberId))))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("/community/" + postId + "/comments")));
    }

    /** 더 보기 전에는 오래된 댓글을 감추고, 요청하면 같은 화면에 펼친다. */
    @Test
    void communityDetail_manyComments_showsLoadMoreForOlderComments() throws Exception {
        long postId = insertPost(memberId, "댓글 많은 글", "본문", PostStatus.PUBLISHED);

        for (int i = 0; i < CommentSectionView.DEFAULT_LIMIT + 1; i++) {
            insertComment(postId, memberId, "댓글 " + i,
                    CommentStatus.PUBLISHED, BASE_TIME.plusMinutes(i));
        }

        int nextLimit = CommentSectionView.DEFAULT_LIMIT + CommentSectionView.STEP;

        mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("댓글 0"))));

        mockMvc.perform(get("/community/" + postId).param("comments", String.valueOf(nextLimit)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("댓글 0")));
    }

    /** 답글은 접힌 채 개수만 보이고, 본문은 펼치기 전에는 응답에 없다. */
    @Test
    void communityDetail_collapsedThread_showsReplyCountWithoutReplyBodies() throws Exception {
        long postId = insertPost(memberId, "답글 달린 글", "본문", PostStatus.PUBLISHED);
        long rootId = insertComment(postId, memberId, "뿌리 댓글",
                CommentStatus.PUBLISHED, BASE_TIME);
        insertReply(postId, rootId, "숨어 있는 답글", CommentStatus.PUBLISHED);
        insertReply(postId, rootId, "지워진 답글 본문", CommentStatus.DELETED);

        mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("답글 2개 보기")))
                .andExpect(content().string(not(containsString("숨어 있는 답글"))));
    }

    /** 펼치면 답글이 오래된 순으로 나오고, 삭제된 답글은 자리 표시로 남는다. */
    @Test
    void communityDetail_expandedThread_showsRepliesWithPlaceholders() throws Exception {
        long postId = insertPost(memberId, "답글 달린 글", "본문", PostStatus.PUBLISHED);
        long rootId = insertComment(postId, memberId, "뿌리 댓글",
                CommentStatus.PUBLISHED, BASE_TIME);
        insertReply(postId, rootId, "보이는 답글", CommentStatus.PUBLISHED);
        insertReply(postId, rootId, "지워진 답글 본문", CommentStatus.DELETED);

        mockMvc.perform(get("/community/" + postId)
                        .param("replies", String.valueOf(rootId))
                        .with(authentication(authorOf(memberId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("보이는 답글")))
                .andExpect(content().string(not(containsString("지워진 답글 본문"))))
                .andExpect(content().string(containsString("삭제된 댓글입니다.")))
                .andExpect(content().string(containsString("답글 접기")))
                .andExpect(content().string(containsString("name=\"replyTo\"")));
    }

    /**
     * 오래된 답글 알림도 전체 GET 한 번으로 부모 묶음과 정확한 앵커를 렌더링한다.
     *
     * <p>표시(`comment-row--focused`)가 붙는 것도 여기서 본다. **어느 줄인지 모르고 들어오는
     * 경로는 알림뿐이고, 표시는 그 물음에만 답한다.**
     */
    @Test
    void communityCommentDeepLink_oldReply_rendersExpandedTarget() throws Exception {
        long postId = insertPost(memberId, "오래된 답글이 있는 글", "본문", PostStatus.PUBLISHED);
        long rootId = insertComment(postId, memberId, "알림 대상 뿌리",
                CommentStatus.PUBLISHED, BASE_TIME);
        long replyId = insertReply(postId, rootId, "알림 대상 답글", CommentStatus.PUBLISHED);

        for (int i = 0; i < CommentSectionView.DEFAULT_LIMIT; i++) {
            insertComment(postId, memberId, "더 최신인 댓글 " + i,
                    CommentStatus.PUBLISHED, BASE_TIME.plusMinutes(i + 1));
        }

        mockMvc.perform(get("/community/" + postId + "/comments/" + replyId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("알림 대상 뿌리")))
                .andExpect(content().string(containsString("알림 대상 답글")))
                .andExpect(content().string(
                        containsString("id=\"comment-" + replyId + "\"")))
                .andExpect(content().string(containsString("답글 접기")))
                .andExpect(content().string(containsString("comment-row--focused")));
    }

    /**
     * 펼치기 링크가 누른 댓글 자리로 돌아온다. 앵커가 없으면 전면 재로드가 문서 맨 위에 착지해
     * 방금 누른 묶음이 화면 밖에 남는다 — 링크는 동작하고 화면도 멀쩡해 고장으로 안 보인다.
     *
     * <p><b>주소 전체를 문자열로 문다.</b> Thymeleaf 는 파라미터 괄호 <b>뒤</b>에 붙인
     * 프래그먼트를 예외 없이 표현식 원문 그대로 href 에 출력한다. 앵커만 따로 확인하면 그
     * 형태가 통과한다.
     *
     * <p>기대값의 빈 {@code comments=} 는 이 변경 전부터 있던 모양이다 — 기본 분량이면
     * {@code limitParam()} 이 null 이고 Thymeleaf 는 null 파라미터를 빈 값으로 그린다. 앵커가
     * <b>그 뒤에</b> 오는 것이 여기서 무는 것이다.
     */
    @Test
    void communityDetail_replyLinks_carryCommentAnchor() throws Exception {
        long postId = insertPost(memberId, "앵커 확인용 글", "본문", PostStatus.PUBLISHED);
        long rootId = insertComment(postId, memberId, "뿌리 댓글",
                CommentStatus.PUBLISHED, BASE_TIME);
        insertReply(postId, rootId, "답글", CommentStatus.PUBLISHED);

        mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "href=\"/community/" + postId + "?comments=&amp;replies=" + rootId
                                + "#comment-" + rootId + "\"")));

        // 접기도 같은 자리로 돌아온다. 접고 나면 그 묶음이 짧아져 더 찾기 어렵다.
        mockMvc.perform(get("/community/" + postId).param("replies", String.valueOf(rootId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("답글 접기")))
                .andExpect(content().string(containsString(
                        "href=\"/community/" + postId + "?comments=#comment-" + rootId + "\"")))
                // 눌러서 온 사람은 어느 댓글인지 안다. 강조는 답이 아니라 소음이다.
                .andExpect(content().string(not(containsString("comment-row--focused"))));
    }

    /**
     * `이전 댓글 더 보기` 는 댓글 구역 머리로 돌아온다.
     *
     * <p>목록은 오래된 것 -> 최신 것 순이라 새로 실린 20개가 <b>위쪽에 끼어든다.</b> 읽던 줄을
     * 앵커로 잡으면 방금 불러온 20개도, 다음 `더 보기` 버튼도 통째로 화면 위 밖에 남아 누른
     * 보람이 없다. 구역 머리에 착지해야 둘이 함께 첫 화면에 들어온다.
     */
    @Test
    void communityDetail_loadMoreLink_anchorsAtCommentSectionHead() throws Exception {
        long postId = insertPost(memberId, "댓글 많은 글", "본문", PostStatus.PUBLISHED);

        for (int i = 0; i <= CommentSectionView.DEFAULT_LIMIT; i++) {
            insertComment(postId, memberId, "댓글 " + i,
                    CommentStatus.PUBLISHED, BASE_TIME.plusMinutes(i));
        }

        mockMvc.perform(get("/community/" + postId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이전 댓글 더 보기")))
                .andExpect(content().string(containsString("id=\"comments\"")))
                .andExpect(content().string(containsString(
                        "href=\"/community/" + postId + "?comments="
                                + (CommentSectionView.DEFAULT_LIMIT + CommentSectionView.STEP)
                                + "#comments\"")));
    }

    /** 답글 작성이 Security 를 통과해 실제로 부모에 연결되어 저장된다. */
    @Test
    void communityReply_authenticated_isStoredUnderParent() throws Exception {
        long postId = insertPost(memberId, "답글 쓸 글", "본문", PostStatus.PUBLISHED);
        long rootId = insertComment(postId, memberId, "뿌리 댓글",
                CommentStatus.PUBLISHED, BASE_TIME);

        MvcResult result = mockMvc.perform(post("/community/" + postId + "/comments")
                        .param("content", "화면에서 단 답글")
                        .param("replyTo", String.valueOf(rootId))
                        .with(authentication(authorOf(memberId)))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Long storedParent = jdbcTemplate.queryForObject(
                "SELECT parent_comment_id FROM comments WHERE content = '화면에서 단 답글'",
                Long.class);
        assertThat(storedParent).isEqualTo(rootId);

        // 묶음을 펼친 채로 방금 쓴 답글 줄에 착지한다. 뿌리가 아니라 새 답글이다.
        Long replyId = jdbcTemplate.queryForObject(
                "SELECT id FROM comments WHERE content = '화면에서 단 답글'", Long.class);
        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("/community/" + postId + "?replies=" + rootId + "#comment-" + replyId);
    }

    /** 삭제된 뿌리를 펼치면 남은 답글은 보이지만 새 답글 폼은 없다. */
    @Test
    void communityDetail_deletedRootThread_hasNoReplyForm() throws Exception {
        long postId = insertPost(memberId, "뿌리 지워진 글", "본문", PostStatus.PUBLISHED);
        long rootId = insertComment(postId, memberId, "지워진 뿌리 본문",
                CommentStatus.DELETED, BASE_TIME);
        insertReply(postId, rootId, "남아 있는 답글", CommentStatus.PUBLISHED);

        mockMvc.perform(get("/community/" + postId)
                        .param("replies", String.valueOf(rootId))
                        .with(authentication(authorOf(memberId))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("남아 있는 답글")))
                .andExpect(content().string(not(containsString("name=\"replyTo\""))));
    }

    /** 댓글 상한에 도달하면 더 펼칠 수 없는 과거 댓글이 있음을 알린다. */
    @Test
    void communityDetail_beyondMaxComments_showsCappedNotice() throws Exception {
        long postId = insertPost(memberId, "댓글 아주 많은 글", "본문", PostStatus.PUBLISHED);

        for (int i = 0; i < CommentSectionView.MAX_LIMIT + 1; i++) {
            insertComment(postId, memberId, "상한 댓글 " + i,
                    CommentStatus.PUBLISHED, BASE_TIME.plusMinutes(i));
        }

        mockMvc.perform(get("/community/" + postId)
                        .param("comments", String.valueOf(CommentSectionView.MAX_LIMIT)))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("오래된 댓글 일부는 표시하지 않습니다.")))
                .andExpect(content().string(not(containsString("이전 댓글 더 보기"))));
    }

    /** 관리자 목록에 실제 게시글을 렌더링한다. */
    @Test
    void communityAdminList_rendersForAdmin() throws Exception {
        insertPost(memberId, "관리자 목록에 보일 글", "본문", PostStatus.PUBLISHED);

        mockMvc.perform(get("/admin/community").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 목록에 보일 글")))
                .andExpect(content().string(containsString("케이크덕후")));
    }

    /** 관리자 상세에 게시글과 신고 내역을 함께 렌더링한다. */
    @Test
    void communityAdminDetail_rendersForAdmin() throws Exception {
        long postId = insertPost(memberId, "관리자 상세 글", "본문입니다", PostStatus.PUBLISHED);
        long reporterId = insertMember(
                "admin-report-" + System.nanoTime() + "@cakeshop.local", "신고한사람", "ACTIVE");
        insertReport(postId, reporterId, "PENDING");

        mockMvc.perform(get("/admin/community/" + postId).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 상세 글")))
                .andExpect(content().string(containsString("본문입니다")))
                .andExpect(content().string(containsString("신고한사람")))
                .andExpect(content().string(containsString("광고성 게시물입니다")));
    }

    /** 신고 사유가 이미지인 경우가 있어 차단을 판단하는 화면이 첨부를 함께 보여 준다. */
    @Test
    void communityAdminDetail_attachedImages_renderForAdmin() throws Exception {
        long postId = insertPost(memberId, "첨부 신고 대상", "본문", PostStatus.BLOCKED);
        insertPostImage(postId, "/uploads/community/202608/reported.jpg", 0);

        mockMvc.perform(get("/admin/community/" + postId).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("reported.jpg")));
    }

    /** 일반 회원의 차단 요청을 Security에서 거절한다. */
    @Test
    void communityAdminBlock_normalMember_isRejected() throws Exception {
        long postId = insertPost(memberId, "차단 시도 대상", "본문", PostStatus.PUBLISHED);

        mockMvc.perform(post("/admin/community/" + postId + "/block")
                        .param("reason", "마음에 안 듦")
                        .with(csrf())
                        .with(authentication(authorOf(memberId))))
                .andExpect(status().isForbidden());

        // 거절된 요청은 게시글을 바꾸지 않는다.
        assertThat(statusOf(postId)).isEqualTo(PostStatus.PUBLISHED.name());
    }

    /** 삭제 글의 신고 기각 요청도 거절한다. */
    @Test
    void communityAdminRejectReports_deletedPost_isRejectedAndLeavesReportPending()
            throws Exception {
        long postId = insertPost(memberId, "지워진 글", "본문", PostStatus.DELETED);
        long reporterId = insertMember(
                "deleted-reject-" + System.nanoTime() + "@cakeshop.local", "신고자", "ACTIVE");
        insertReport(postId, reporterId, "PENDING");

        mockMvc.perform(post("/admin/community/" + postId + "/reports/reject")
                        .with(csrf())
                        .with(authentication(admin())))
                .andExpect(status().isBadRequest());

        // 거절된 기각 요청은 신고 상태를 바꾸지 않는다.
        assertThat(reportStatusOf(postId)).isEqualTo("PENDING");
    }

    /** 관리자 공지 목록에 네 가지 노출 상태를 모두 렌더링한다. */
    @Test
    void communityAdminNoticeList_rendersEveryDisplayStatus() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        insertNotice("지금 보이는 공지", NoticeStatus.PUBLISHED, null, null);
        insertNotice("예정된 공지", NoticeStatus.PUBLISHED, now.plusDays(1), now.plusDays(2));
        insertNotice("끝난 공지", NoticeStatus.PUBLISHED, now.minusDays(2), now.minusDays(1));
        insertNotice("지운 공지", NoticeStatus.DELETED, null, null);

        mockMvc.perform(get("/admin/community/notices").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("지금 보이는 공지")))
                .andExpect(content().string(containsString("노출 중")))
                .andExpect(content().string(containsString("예정")))
                .andExpect(content().string(containsString("종료")))
                .andExpect(content().string(containsString("삭제됨")))
                .andExpect(content().string(containsString("무기한")));
    }

    /** 공지는 GNB의 독립 메뉴로 진입하고 커뮤니티 게시글 목록에는 섞이지 않는다. */
    @Test
    void communityList_withVisibleNotice_keepsNoticeOutAndRendersGnbEntryPoint()
            throws Exception {
        insertNotice("공지 목록에서만 보이는 안내", NoticeStatus.PUBLISHED, null, null);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("공지 목록에서만 보이는 안내"))))
                .andExpect(content().string(containsString(
                        "<a href=\"/community/notices\">공지사항</a>")));
    }

    /** 전체보기는 비로그인도 열 수 있고, 노출 중인 공지만 그린다. */
    @Test
    void communityNoticeList_anonymous_rendersVisibleNoticesOnly() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        insertNotice("보이는 공지", NoticeStatus.PUBLISHED, null, null);
        insertNotice("예정된 공지", NoticeStatus.PUBLISHED, now.plusDays(1), now.plusDays(2));
        insertNotice("끝난 공지", NoticeStatus.PUBLISHED, now.minusDays(2), now.minusDays(1));
        insertNotice("지운 공지", NoticeStatus.DELETED, null, null);

        mockMvc.perform(get("/community/notices"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("보이는 공지")))
                .andExpect(content().string(not(containsString("예정된 공지"))))
                .andExpect(content().string(not(containsString("끝난 공지"))))
                .andExpect(content().string(not(containsString("지운 공지"))));
    }

    /** 공지 상세에는 댓글·좋아요·신고 경로가 없다. */
    @Test
    void communityNoticeDetail_anonymous_rendersContentWithoutPostActions() throws Exception {
        long noticeId = insertNotice("읽을 수 있는 공지", NoticeStatus.PUBLISHED, null, null);

        mockMvc.perform(get("/community/notices/" + noticeId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("읽을 수 있는 공지")))
                .andExpect(content().string(containsString("본문")))
                .andExpect(content().string(not(containsString("/likes"))))
                .andExpect(content().string(not(containsString("/reports"))))
                .andExpect(content().string(not(containsString("/comments"))));
    }

    /** 공지 본문의 HTML도 이스케이프한다. */
    @Test
    void communityNoticeDetail_htmlInContent_isEscaped() throws Exception {
        String attack = "<script>alert('notice')</script>";
        long noticeId = insertNotice("스크립트 공지", NoticeStatus.PUBLISHED, null, null);
        jdbcTemplate.update(
                "UPDATE community_notices SET content = ? WHERE id = ?", attack, noticeId);

        mockMvc.perform(get("/community/notices/" + noticeId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(attack))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    /** 노출 기간 밖과 삭제된 공지는 관리자에게도 고객 경로에서 404다. */
    @Test
    void communityNoticeDetail_outsidePeriodOrDeleted_isNotFoundEvenForAdmin() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        long scheduled = insertNotice("예정", NoticeStatus.PUBLISHED, now.plusDays(1), null);
        long ended = insertNotice("종료", NoticeStatus.PUBLISHED, null, now.minusDays(1));
        long deleted = insertNotice("삭제됨", NoticeStatus.DELETED, null, null);

        for (long noticeId : new long[]{scheduled, ended, deleted}) {
            mockMvc.perform(get("/community/notices/" + noticeId))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/community/notices/" + noticeId)
                            .with(authentication(admin())))
                    .andExpect(status().isNotFound());
        }
    }

    /** 수정 화면에 저장된 노출 기간을 채운다. */
    @Test
    void communityAdminNoticeEditForm_rendersStoredPeriod() throws Exception {
        long noticeId = insertNotice(
                "고칠 공지",
                NoticeStatus.PUBLISHED,
                LocalDateTime.of(2026, 3, 2, 9, 0),
                LocalDateTime.of(2026, 3, 9, 9, 0));

        mockMvc.perform(get("/admin/community/notices/" + noticeId + "/edit")
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("고칠 공지")))
                .andExpect(content().string(containsString("2026-03-02T09:00")))
                .andExpect(content().string(containsString("2026-03-09T09:00")));
    }

    /** 삭제된 공지는 수정 폼 자체가 열리지 않는다. 목록에 링크가 없어도 주소로는 닿는다. */
    @Test
    void communityAdminNoticeEditForm_deletedNotice_isRejectedBeforeRendering() throws Exception {
        long noticeId = insertNotice("지운 공지", NoticeStatus.DELETED, null, null);

        mockMvc.perform(get("/admin/community/notices/" + noticeId + "/edit")
                        .with(authentication(admin())))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("지운 공지"))));
    }

    /** 뒤집힌 노출 기간은 폼 오류로 되돌아오고 아무것도 저장하지 않는다. */
    @Test
    void communityAdminNoticeCreate_invertedPeriod_rendersFormErrorAndWritesNothing()
            throws Exception {
        mockMvc.perform(post("/admin/community/notices/new")
                        .param("title", "뒤집힌 기간 공지")
                        .param("content", "본문")
                        .param("startsAt", "2026-03-09T09:00")
                        .param("endsAt", "2026-03-02T09:00")
                        .with(csrf())
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("종료일은 시작일보다 뒤여야 합니다.")));

        assertThat(noticeCountOf("뒤집힌 기간 공지")).isZero();
    }

    /** 일반 회원의 공지 등록 요청을 Security에서 거절한다. */
    @Test
    void communityAdminNoticeCreate_normalMember_isRejectedAndWritesNothing() throws Exception {
        mockMvc.perform(post("/admin/community/notices/new")
                        .param("title", "몰래 올린 공지")
                        .param("content", "본문")
                        .with(csrf())
                        .with(authentication(authorOf(memberId))))
                .andExpect(status().isForbidden());

        assertThat(noticeCountOf("몰래 올린 공지")).isZero();
    }

    /** 일반 회원의 공지 삭제 요청도 거절하고 상태를 남긴다. */
    @Test
    void communityAdminNoticeDelete_normalMember_isRejectedAndLeavesStatus() throws Exception {
        long noticeId = insertNotice("지켜져야 하는 공지", NoticeStatus.PUBLISHED, null, null);

        mockMvc.perform(post("/admin/community/notices/" + noticeId + "/delete")
                        .with(csrf())
                        .with(authentication(authorOf(memberId))))
                .andExpect(status().isForbidden());

        assertThat(noticeStatusOf(noticeId)).isEqualTo(NoticeStatus.PUBLISHED.name());
    }

    private long insertNotice(
            String title, NoticeStatus status, LocalDateTime startsAt, LocalDateTime endsAt) {

        jdbcTemplate.update(
                """
                INSERT INTO community_notices (
                    title, content, status, starts_at, ends_at, created_by
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                title, "본문", status.name(), startsAt, endsAt, memberId);

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String noticeStatusOf(long noticeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM community_notices WHERE id = ?", String.class, noticeId);
    }

    private Integer noticeCountOf(String title) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_notices WHERE title = ?", Integer.class, title);
    }

    private void insertRanking(int ranking, long postId) {
        jdbcTemplate.update(
                """
                INSERT INTO daily_popular_posts (
                    ranking_date, ranking, post_id, popularity_score,
                    view_count, like_count, comment_count
                )
                VALUES (?, ?, ?, 100, 0, 0, 0)
                """,
                RANKING_DATE, ranking, postId);
    }

    private void insertBatchRun(int postCount) {
        jdbcTemplate.update(
                """
                INSERT INTO popular_post_batch_runs (ranking_date, post_count)
                VALUES (?, ?)
                """,
                RANKING_DATE, postCount);
    }

    private String statusOf(long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM posts WHERE id = ?", String.class, postId);
    }

    private String reportStatusOf(long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM post_reports WHERE post_id = ?", String.class, postId);
    }

    private Authentication authorOf(long id) {
        return authenticationOf(id, "USER");
    }

    private Authentication admin() {
        return authenticationOf(1L, "ADMIN");
    }

    private Authentication authenticationOf(long id, String role) {
        MemberDetails principal = new MemberDetails(
                new MemberAuthenticationView(id, "viewer@cakeshop.local", "x", role, true));

        return new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    private long insertCategory(String code, String name) {
        jdbcTemplate.update(
                """
                INSERT INTO post_categories (code, name, is_active, sort_order)
                VALUES (?, ?, 1, 999)
                """,
                code, name);

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertInactiveCategory() {
        jdbcTemplate.update(
                """
                INSERT INTO post_categories (code, name, is_active, sort_order)
                VALUES (?, '비활성분류', 0, 999)
                """,
                "SCREEN_INACTIVE_" + System.nanoTime());
    }

    private long insertMember(String email, String nickname, String status) {
        return insertMember(email, nickname, status, "USER");
    }

    private long insertAdminMember() {
        return insertMember(
                "screen-admin-" + System.nanoTime() + "@cakeshop.local", "관리자", "ACTIVE", "ADMIN");
    }

    private long insertMember(String email, String nickname, String status, String role) {
        jdbcTemplate.update(
                """
                INSERT INTO members (
                    email, password, nickname, phone, role, status, name, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                email, "encoded-password", nickname, "010-0000-0000", role, status,
                nickname, BASE_TIME, BASE_TIME);

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertPost(long authorId, String title, String content, PostStatus status) {
        jdbcTemplate.update(
                """
                INSERT INTO posts (
                    member_id, category_id, title, content, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                authorId, categoryId, title, content, status.name(), BASE_TIME, BASE_TIME);

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertPostImage(long postId, String imageUrl, int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO post_images (post_id, image_url, sort_order)
                VALUES (?, ?, ?)
                """,
                postId, imageUrl, sortOrder);
    }

    private void insertReport(long postId, long reporterId, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO post_reports (post_id, reporter_id, reason, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                postId, reporterId, "광고성 게시물입니다", status, BASE_TIME);
    }

    private long insertComment(
            long postId,
            long authorId,
            String content,
            CommentStatus status,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO comments (
                    post_id, member_id, content, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                postId, authorId, content, status.name(), createdAt, createdAt);

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertReply(long postId, long parentId, String content, CommentStatus status) {
        jdbcTemplate.update(
                """
                INSERT INTO comments (
                    post_id, member_id, parent_comment_id, content, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                postId, memberId, parentId, content, status.name(), BASE_TIME, BASE_TIME);

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Integer commentCountOf(long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments WHERE post_id = ?", Integer.class, postId);
    }

    private String commentStatusOf(long commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM comments WHERE id = ?", String.class, commentId);
    }

    private Integer likeCountOf(long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_likes WHERE post_id = ?", Integer.class, postId);
    }

    private Integer reportCountOf(long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_reports WHERE post_id = ?", Integer.class, postId);
    }
}
