package com.cakeshop.domain.community.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.community.dto.query.AdminPostDetailRow;
import com.cakeshop.domain.community.dto.view.AdminPostDetailView;
import com.cakeshop.domain.community.dto.query.AdminPostListRow;
import com.cakeshop.domain.community.dto.view.AdminPostListView;
import com.cakeshop.domain.community.dto.view.AdminPostSort;
import com.cakeshop.domain.community.dto.query.PostLockRow;
import com.cakeshop.domain.community.dto.query.ReportRow;
import com.cakeshop.domain.community.dto.view.ReportView;
import com.cakeshop.domain.community.entity.PostStatus;
import com.cakeshop.domain.community.entity.ReportStatus;
import com.cakeshop.domain.community.error.CommunityErrorCode;
import com.cakeshop.domain.community.mapper.CommunityAdminMapper;
import com.cakeshop.domain.community.mapper.CommunityMapper;
import com.cakeshop.domain.member.dto.view.MemberCommunityView;
import com.cakeshop.domain.member.service.MemberCommunityQueryService;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.common.paging.PageResult;
import com.cakeshop.global.error.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

/** 관리자 조치 규칙을 검증한다. */
class CommunityAdminServiceTests {

    private static final long POST_ID = 42L;
    private static final long AUTHOR_ID = 7L;
    private static final long ADMIN_ID = 1L;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 3, 1, 10, 0);

    private CommunityAdminMapper communityAdminMapper;

    private CommunityMapper communityMapper;

    private MemberCommunityQueryService memberCommunityQueryService;

    private CommunityAdminService communityAdminService;

    @BeforeEach
    void setUp() {
        communityAdminMapper = mock(CommunityAdminMapper.class);
        communityMapper = mock(CommunityMapper.class);
        memberCommunityQueryService = mock(MemberCommunityQueryService.class);
        communityAdminService = new CommunityAdminService(
                communityAdminMapper,
                communityMapper,
                new CommunityMemberViewLoader(memberCommunityQueryService));
    }

    /** 차단은 잠금, 상태 변경, 신고 종료 순서로 처리한다. */
    @Test
    void blockPost_publishedPost_locksThenBlocksThenClosesReports() {
        givenLockedPost(PostStatus.PUBLISHED);
        when(communityAdminMapper.blockPost(POST_ID, "광고성 게시물", ADMIN_ID)).thenReturn(1);

        communityAdminService.blockPost(POST_ID, "광고성 게시물", ADMIN_ID);

        InOrder order = inOrder(communityMapper, communityAdminMapper);
        order.verify(communityMapper).lockPost(POST_ID);
        order.verify(communityAdminMapper).blockPost(POST_ID, "광고성 게시물", ADMIN_ID);
        order.verify(communityAdminMapper).closePendingReports(POST_ID, ReportStatus.RESOLVED);
    }

    @ParameterizedTest
    @EnumSource(value = PostStatus.class, names = {"BLOCKED", "DELETED"})
    void blockPost_nonPublishedPost_isRejected(PostStatus status) {
        givenLockedPost(status);

        assertThatThrownBy(() -> communityAdminService.blockPost(POST_ID, "사유", ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.INVALID_POST_TRANSITION);

        verify(communityAdminMapper, never()).blockPost(anyLong(), any(), anyLong());
        verify(communityAdminMapper, never()).closePendingReports(anyLong(), any());
    }

    @Test
    void blockPost_missingPost_isRejectedAsNotFound() {
        when(communityMapper.lockPost(POST_ID)).thenReturn(null);

        assertThatThrownBy(() -> communityAdminService.blockPost(POST_ID, "사유", ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);
    }

    /** 차단 갱신이 0행이면 신고를 종료하지 않는다. */
    @Test
    void blockPost_updateAffectedNoRow_doesNotCloseReports() {
        givenLockedPost(PostStatus.PUBLISHED);
        when(communityAdminMapper.blockPost(POST_ID, "사유", ADMIN_ID)).thenReturn(0);

        assertThatThrownBy(() -> communityAdminService.blockPost(POST_ID, "사유", ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.INVALID_POST_TRANSITION);

        verify(communityAdminMapper, never()).closePendingReports(anyLong(), any());
    }

    /** 차단 해제는 종료된 신고를 되돌리지 않는다. */
    @Test
    void unblockPost_blockedPost_leavesReportsClosed() {
        givenLockedPost(PostStatus.BLOCKED);
        when(communityAdminMapper.unblockPost(POST_ID)).thenReturn(1);

        communityAdminService.unblockPost(POST_ID);

        verify(communityAdminMapper).unblockPost(POST_ID);
        verify(communityAdminMapper, never()).closePendingReports(anyLong(), any());
    }

    /** 공개 글은 차단 해제할 수 없다. */
    @Test
    void unblockPost_publishedPost_isRejected() {
        givenLockedPost(PostStatus.PUBLISHED);

        assertThatThrownBy(() -> communityAdminService.unblockPost(POST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.INVALID_POST_TRANSITION);

        verify(communityAdminMapper, never()).unblockPost(anyLong());
    }

    /** 신고 기각은 게시글을 변경하지 않는다. */
    @Test
    void rejectReports_pendingReports_closesThemWithoutTouchingPost() {
        givenLockedPost(PostStatus.PUBLISHED);
        when(communityAdminMapper.closePendingReports(POST_ID, ReportStatus.REJECTED)).thenReturn(2);

        communityAdminService.rejectReports(POST_ID);

        // 신고 기각도 게시글 잠금부터 처리한다.
        InOrder order = inOrder(communityMapper, communityAdminMapper);
        order.verify(communityMapper).lockPost(POST_ID);
        order.verify(communityAdminMapper).closePendingReports(POST_ID, ReportStatus.REJECTED);
        verify(communityAdminMapper, never()).blockPost(anyLong(), any(), anyLong());
        verify(communityAdminMapper, never()).unblockPost(anyLong());
    }

    /** 삭제 글의 신고는 기각할 수 없다. */
    @Test
    void rejectReports_deletedPost_isRejected() {
        givenLockedPost(PostStatus.DELETED);

        assertThatThrownBy(() -> communityAdminService.rejectReports(POST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.INVALID_POST_TRANSITION);

        verify(communityAdminMapper, never()).closePendingReports(anyLong(), any());
    }

    /** 차단 후 접수된 신고는 기각할 수 있다. */
    @Test
    void rejectReports_blockedPost_isAllowed() {
        givenLockedPost(PostStatus.BLOCKED);
        when(communityAdminMapper.closePendingReports(POST_ID, ReportStatus.REJECTED)).thenReturn(1);

        communityAdminService.rejectReports(POST_ID);

        verify(communityAdminMapper).closePendingReports(POST_ID, ReportStatus.REJECTED);
    }

    /** 미처리 신고가 없으면 기각 성공으로 처리하지 않는다. */
    @Test
    void rejectReports_withoutPendingReports_isRejected() {
        givenLockedPost(PostStatus.PUBLISHED);
        when(communityAdminMapper.closePendingReports(POST_ID, ReportStatus.REJECTED)).thenReturn(0);

        assertThatThrownBy(() -> communityAdminService.rejectReports(POST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.INVALID_POST_TRANSITION);
    }

    /** 없는 글의 신고는 기각할 수 없다. */
    @Test
    void rejectReports_missingPost_isRejectedAsNotFound() {
        when(communityMapper.lockPost(POST_ID)).thenReturn(null);

        assertThatThrownBy(() -> communityAdminService.rejectReports(POST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);

        verify(communityAdminMapper, never()).closePendingReports(anyLong(), any());
    }

    /** 관리자 상세도 없는 글에는 404를 반환한다. */
    @Test
    void getPostDetail_missingPost_isRejectedAsNotFound() {
        when(communityAdminMapper.findPostByIdForAdmin(POST_ID)).thenReturn(null);

        assertThatThrownBy(() -> communityAdminService.getPostDetail(POST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.POST_NOT_FOUND);
    }

    /** 관리자 목록 작성자를 회원 계약으로 채운다. */
    @Test
    void getPosts_fillsAuthorFromMemberContract() {
        when(communityAdminMapper.findPostsForAdmin(null, AdminPostSort.LATEST, 20, 0))
                .thenReturn(List.of(new AdminPostListRow(
                        POST_ID, AUTHOR_ID, "질문", "제목", PostStatus.PUBLISHED, 0, CREATED_AT)));
        when(communityAdminMapper.countPostsForAdmin(null)).thenReturn(1L);
        givenMembers(new MemberCommunityView(AUTHOR_ID, "글쓴이", false, false));

        PageResult<AdminPostListView> result =
                communityAdminService.getPosts(null, AdminPostSort.LATEST, new PageRequest(1, 20));

        assertThat(result.getContent()).singleElement()
                .satisfies(post -> assertThat(post.authorName()).isEqualTo("글쓴이"));
    }

    /** 회원 정보가 없으면 작성자만 가린다. */
    @Test
    void getPosts_missingAuthor_keepsPostAndMasksAuthor() {
        when(communityAdminMapper.findPostsForAdmin(null, AdminPostSort.LATEST, 20, 0))
                .thenReturn(List.of(new AdminPostListRow(
                        POST_ID, AUTHOR_ID, "질문", "제목", PostStatus.PUBLISHED, 0, CREATED_AT)));
        when(communityAdminMapper.countPostsForAdmin(null)).thenReturn(1L);
        givenMembers();

        PageResult<AdminPostListView> result =
                communityAdminService.getPosts(null, AdminPostSort.LATEST, new PageRequest(1, 20));

        assertThat(result.getContent()).singleElement()
                .satisfies(post -> assertThat(post.authorName()).isEqualTo("탈퇴한 회원"));
    }

    /** 신고자를 회원 계약으로 채운다. */
    @Test
    void getReports_fillsReporterFromMemberContract() {
        when(communityAdminMapper.findReportsByPost(POST_ID)).thenReturn(List.of(
                new ReportRow(1L, AUTHOR_ID, "광고입니다", ReportStatus.PENDING, CREATED_AT),
                new ReportRow(2L, ADMIN_ID, "욕설입니다", ReportStatus.PENDING, CREATED_AT)));
        givenMembers(
                new MemberCommunityView(AUTHOR_ID, "신고자", false, false),
                new MemberCommunityView(ADMIN_ID, "탈퇴자", true, false));

        assertThat(communityAdminService.getReports(POST_ID))
                .extracting(ReportView::reporterName)
                .containsExactly("신고자", "탈퇴한 회원");
    }

    @Test
    void getPostDetail_neverBlocked_leavesBlockingAdminEmpty() {
        when(communityAdminMapper.findPostByIdForAdmin(POST_ID))
                .thenReturn(adminDetailRow(null));
        givenMembers(new MemberCommunityView(AUTHOR_ID, "글쓴이", false, false));

        AdminPostDetailView post = communityAdminService.getPostDetail(POST_ID);

        assertThat(post.blockedByNickname()).isNull();
        assertThat(post.hasBlockRecord()).isFalse();
    }

    private AdminPostDetailRow adminDetailRow(Long blockedBy) {
        return new AdminPostDetailRow(
                POST_ID,
                AUTHOR_ID,
                "질문",
                "제목",
                "본문",
                blockedBy == null ? PostStatus.PUBLISHED : PostStatus.BLOCKED,
                blockedBy == null ? null : "광고성 게시물",
                blockedBy == null ? null : CREATED_AT,
                blockedBy,
                10L,
                2L,
                CREATED_AT,
                CREATED_AT
        );
    }

    private void givenMembers(MemberCommunityView... members) {
        when(memberCommunityQueryService.getMembersByIds(anyList()))
                .thenReturn(List.of(members));
    }

    private void givenLockedPost(PostStatus status) {
        when(communityMapper.lockPost(POST_ID))
                .thenReturn(new PostLockRow(AUTHOR_ID, status));
    }

}
