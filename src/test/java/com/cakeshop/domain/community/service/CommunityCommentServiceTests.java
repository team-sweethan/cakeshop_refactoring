package com.cakeshop.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import com.cakeshop.domain.community.dto.form.CommentForm;
import com.cakeshop.domain.community.dto.query.CommentCountRow;
import com.cakeshop.domain.community.dto.query.CommentRow;
import com.cakeshop.domain.community.dto.query.PostDetailRow;
import com.cakeshop.domain.community.dto.query.ReplyCountRow;
import com.cakeshop.domain.community.dto.view.CommentSectionView;
import com.cakeshop.domain.community.dto.view.CommentView;
import com.cakeshop.domain.community.entity.Comment;
import com.cakeshop.domain.community.entity.CommentStatus;
import com.cakeshop.domain.community.entity.PostStatus;
import com.cakeshop.domain.community.error.CommunityErrorCode;
import com.cakeshop.domain.community.mapper.CommunityCommentMapper;
import com.cakeshop.domain.community.mapper.CommunityMapper;
import com.cakeshop.domain.community.mapper.CommunityPopularPostMapper;
import com.cakeshop.domain.member.dto.view.MemberCommunityView;
import com.cakeshop.domain.member.service.MemberCommunityQueryService;
import com.cakeshop.global.error.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/** 커뮤니티 댓글 Service의 권한과 상태 계약을 확인한다. */
class CommunityCommentServiceTests {

    private static final long POST_ID = 42L;
    private static final long COMMENT_ID = 314L;
    /** 매퍼가 생성 키로 돌려주는 새 댓글·답글의 id. */
    private static final long NEW_COMMENT_ID = 315L;
    private static final long AUTHOR_ID = 7L;
    private static final long OTHER_MEMBER_ID = 99L;
    /** 요청 대상이 아닌 다른 글. */
    private static final long OTHER_POST_ID = 999L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 3, 1, 10, 0);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private CommunityMapper communityMapper;
    private CommunityCommentMapper communityCommentMapper;
    private MemberCommunityQueryService memberCommunityQueryService;
    private CommunityCommentNotificationService communityCommentNotificationService;
    private CommunityCommentService communityCommentService;

    @BeforeEach
    void setUp() {
        communityMapper = mock(CommunityMapper.class);
        communityCommentMapper = mock(CommunityCommentMapper.class);
        memberCommunityQueryService = mock(MemberCommunityQueryService.class);
        communityCommentNotificationService = mock(CommunityCommentNotificationService.class);
        when(memberCommunityQueryService.getMembersByIds(anyList())).thenReturn(List.of());

        CommunityMemberViewLoader memberViewLoader =
                new CommunityMemberViewLoader(memberCommunityQueryService);

        communityCommentService = new CommunityCommentService(
                communityCommentMapper,
                memberViewLoader,
                postService(memberViewLoader),
                communityCommentNotificationService);
    }

    /*
     * 게시글 Service를 목으로 갈지 않는다. 댓글을 달 수 있는 글인지 판단하는 것은 그쪽이고,
     * 목으로 두면 아래 차단 글 표가 아무것도 검증하지 않게 된다.
     */
    private CommunityPostService postService(CommunityMemberViewLoader memberViewLoader) {
        return new CommunityPostService(
                communityMapper,
                memberViewLoader,
                new CommunityPostAccessPolicy(),
                new PopularPostReader(
                        mock(CommunityPopularPostMapper.class),
                        Clock.system(SEOUL)),
                mock(CommunityPostImageService.class));
    }

    /** 댓글 작성자를 회원 계약으로 채운다. */
    @Test
    void getComments_fillsAuthorFromMemberContract() {
        givenComments(commentOf(1L, CommentStatus.PUBLISHED));
        givenAuthors(new MemberCommunityView(AUTHOR_ID, "글쓴이", false, false));

        CommentSectionView section = communityCommentService.getComments(POST_ID, null, null);

        assertThat(section.threads()).singleElement()
                .satisfies(thread -> assertThat(thread.root().authorName()).isEqualTo("글쓴이"));
    }

    /** 탈퇴 회원의 댓글 작성자명을 가린다. */
    @Test
    void getComments_withdrawnAuthor_showsPlaceholderName() {
        givenComments(commentOf(1L, CommentStatus.PUBLISHED));
        givenAuthors(new MemberCommunityView(AUTHOR_ID, "글쓴이", true, false));

        CommentSectionView section = communityCommentService.getComments(POST_ID, null, null);

        assertThat(section.threads()).singleElement()
                .satisfies(thread -> assertThat(thread.root().authorName()).isEqualTo("탈퇴한 회원"));
    }

    /** 최신 뿌리를 오래된 순으로 보여 준다. */
    @Test
    void getComments_returnsRootsOldestFirst() {
        givenComments(
                commentOf(3L, CommentStatus.PUBLISHED),
                commentOf(2L, CommentStatus.PUBLISHED),
                commentOf(1L, CommentStatus.PUBLISHED));

        CommentSectionView section = communityCommentService.getComments(POST_ID, null, null);

        assertThat(section.threads()).extracting(thread -> thread.root().id())
                .containsExactly(1L, 2L, 3L);
    }

    /** 요청값이 없거나 너무 작으면 기본값을, 너무 크면 상한을 사용한다. */
    @ParameterizedTest(name = "요청 limit={0}이면 조회 limit={1}")
    @MethodSource("requestedCommentLimits")
    void getComments_requestedLimit_isClampedToAllowedRange(Integer requested, int expected) {
        givenComments();

        communityCommentService.getComments(POST_ID, requested, null);

        verify(communityCommentMapper).findRecentRootComments(POST_ID, expected);
    }

    private static Stream<Arguments> requestedCommentLimits() {
        return Stream.of(
                arguments(null, CommentSectionView.DEFAULT_LIMIT),
                arguments(1, CommentSectionView.DEFAULT_LIMIT),
                arguments(Integer.MAX_VALUE, CommentSectionView.MAX_LIMIT));
    }

    /** 표시 댓글 수와 더 보기 판단을 구분한다 — 앞은 노출 중 전체, 뒤는 뿌리 행 수다. */
    @Test
    void getComments_countsPlaceholdersForLoadMoreButNotForDisplayedCount() {
        when(communityCommentMapper.findRecentRootComments(anyLong(), anyInt()))
                .thenReturn(List.of(commentOf(1L, CommentStatus.DELETED)));
        when(communityCommentMapper.countComments(POST_ID)).thenReturn(new CommentCountRow(5L, 3L));

        CommentSectionView section = communityCommentService.getComments(POST_ID, null, null);

        assertThat(section.publishedCount()).isEqualTo(3L);
        assertThat(section.hasMore()).isTrue();
        assertThat(section.hiddenCount()).isEqualTo(4L);
    }

    /** 뿌리별 답글 수를 접힌 채로 싣는다. 자리 표시도 센다 — 펼치는 길이 사라지면 안 된다. */
    @Test
    void getComments_carriesReplyCountPerRootWithoutLoadingReplies() {
        givenComments(commentOf(1L, CommentStatus.PUBLISHED));
        when(communityCommentMapper.countRepliesByParentIds(List.of(1L)))
                .thenReturn(List.of(new ReplyCountRow(1L, 3L)));

        CommentSectionView section = communityCommentService.getComments(POST_ID, null, null);

        assertThat(section.threads()).singleElement().satisfies(thread -> {
            assertThat(thread.replyCount()).isEqualTo(3L);
            assertThat(thread.expanded()).isFalse();
            assertThat(thread.replies()).isEmpty();
        });
        verify(communityCommentMapper, never())
                .findRepliesByParentId(anyLong(), anyLong(), anyInt());
    }

    /** 요청한 뿌리만 펼치고, 최신 쪽으로 받은 답글을 오래된 순으로 뒤집어 싣는다. */
    @Test
    void getComments_expandedRoot_loadsRepliesOldestFirst() {
        givenComments(
                commentOf(2L, CommentStatus.PUBLISHED),
                commentOf(1L, CommentStatus.PUBLISHED));
        when(communityCommentMapper.countRepliesByParentIds(List.of(2L, 1L)))
                .thenReturn(List.of(new ReplyCountRow(1L, 2L)));
        when(communityCommentMapper.findRepliesByParentId(
                1L, POST_ID, CommentSectionView.MAX_LIMIT))
                .thenReturn(List.of(
                        commentOf(11L, CommentStatus.DELETED),
                        commentOf(10L, CommentStatus.PUBLISHED)));

        CommentSectionView section = communityCommentService.getComments(POST_ID, null, 1L);

        assertThat(section.threads()).hasSize(2);
        assertThat(section.threads().get(0).expanded()).isTrue();
        assertThat(section.threads().get(0).replies()).extracting(CommentView::id)
                .containsExactly(10L, 11L);
        assertThat(section.threads().get(1).expanded()).isFalse();
    }

    /** 창 밖의 뿌리는 펼치지 않는다 — 주소로 임의 id가 들어와도 조회하지 않는다. */
    @Test
    void getComments_expandedRootOutsideWindow_isIgnored() {
        givenComments(commentOf(1L, CommentStatus.PUBLISHED));

        CommentSectionView section = communityCommentService.getComments(POST_ID, null, 99L);

        assertThat(section.threads()).singleElement()
                .satisfies(thread -> assertThat(thread.expanded()).isFalse());
        verify(communityCommentMapper, never())
                .findRepliesByParentId(anyLong(), anyLong(), anyInt());
    }

    /** 알림 대상 뿌리가 기본 창 밖이어도 최신 목록과 함께 제한 안에서 싣는다. */
    @Test
    void getFocusedComments_rootOutsideWindow_isIncluded() {
        CommentRow recent = commentOf(20L, CommentStatus.PUBLISHED);
        CommentRow focused = commentOf(1L, CommentStatus.PUBLISHED);
        givenComments(recent);
        when(communityCommentMapper.findCommentById(1L)).thenReturn(focused);
        when(communityCommentMapper.countComments(POST_ID)).thenReturn(new CommentCountRow(2L, 2L));

        CommentSectionView section = communityCommentService.getFocusedComments(POST_ID, 1L);

        assertThat(section.threads()).extracting(thread -> thread.root().id())
                .containsExactly(1L, 20L);
    }

    /** 알림 대상 답글이면 창 밖 뿌리를 포함하고 그 묶음을 서버에서 펼친다. */
    @Test
    void getFocusedComments_reply_expandsItsRootAndIncludesTarget() {
        CommentRow recent = commentOf(20L, CommentStatus.PUBLISHED);
        CommentRow root = commentOf(1L, CommentStatus.PUBLISHED);
        CommentRow reply = replyOf(11L, 1L, CommentStatus.PUBLISHED);
        givenComments(recent);
        when(communityCommentMapper.findCommentById(11L)).thenReturn(reply);
        when(communityCommentMapper.findCommentById(1L)).thenReturn(root);
        when(communityCommentMapper.countComments(POST_ID)).thenReturn(new CommentCountRow(2L, 3L));
        when(communityCommentMapper.countRepliesByParentIds(List.of(20L, 1L)))
                .thenReturn(List.of(new ReplyCountRow(1L, 1L)));
        when(communityCommentMapper.findRepliesByParentId(
                1L, POST_ID, CommentSectionView.MAX_LIMIT))
                .thenReturn(List.of());

        CommentSectionView section = communityCommentService.getFocusedComments(POST_ID, 11L);

        assertThat(section.threads()).first().satisfies(thread -> {
            assertThat(thread.root().id()).isEqualTo(1L);
            assertThat(thread.expanded()).isTrue();
            assertThat(thread.replies()).extracting(CommentView::id).containsExactly(11L);
        });
    }

    /** 다른 글의 댓글 id를 섞은 deep link는 대상 댓글을 숨겨 404로 보낸다. */
    @Test
    void getFocusedComments_commentFromAnotherPost_isRejected() {
        when(communityCommentMapper.findCommentById(COMMENT_ID))
                .thenReturn(commentOf(
                        COMMENT_ID, OTHER_POST_ID, AUTHOR_ID, CommentStatus.PUBLISHED));

        assertThatThrownBy(() -> communityCommentService.getFocusedComments(POST_ID, COMMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    void addReply_savesThroughConditionalInsert() {
        givenPost(PostStatus.PUBLISHED);
        givenInsertedReply();

        communityCommentService.addReply(
                POST_ID, COMMENT_ID, commentFormOf("답글 본문"), AUTHOR_ID);

        Comment saved = capturedReply();
        assertThat(saved.getPostId()).isEqualTo(POST_ID);
        assertThat(saved.getParentCommentId()).isEqualTo(COMMENT_ID);
        assertThat(saved.getMemberId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getContent()).isEqualTo("답글 본문");
    }

    /** 0행이면 거절이다 — 부모 없음·다른 글·답글의 답글·삭제된 부모가 전부 이 한 자리로 모인다. */
    @Test
    void addReply_zeroRows_isRejected() {
        givenPost(PostStatus.PUBLISHED);
        when(communityCommentMapper.insertReply(any())).thenReturn(0);

        assertThatThrownBy(() -> communityCommentService.addReply(
                POST_ID, COMMENT_ID, commentFormOf("답글 본문"), AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND);
    }

    /** 거절된 답글은 부모를 읽지도 않는다 — 그 읽기는 알림 수신자를 고르는 것뿐이다. */
    @Test
    void addReply_zeroRows_notifiesNobody() {
        givenPost(PostStatus.PUBLISHED);
        when(communityCommentMapper.insertReply(any())).thenReturn(0);

        assertThatThrownBy(() -> communityCommentService.addReply(
                POST_ID, COMMENT_ID, commentFormOf("답글 본문"), AUTHOR_ID))
                .isInstanceOf(BusinessException.class);

        verify(communityCommentNotificationService, never())
                .notifyNewReply(anyLong(), anyLong(), anyLong(), anyLong());
    }

    /** 댓글 알림은 글 작성자에게 가고, 수신자를 글 조회 결과에서 가져온다. */
    @Test
    void addComment_notifiesPostAuthorWithNewCommentId() {
        givenPost(PostStatus.PUBLISHED);
        givenInsertedComment(NEW_COMMENT_ID);

        communityCommentService.addComment(POST_ID, commentFormOf("댓글 본문"), OTHER_MEMBER_ID);

        verify(communityCommentNotificationService)
                .notifyNewComment(POST_ID, NEW_COMMENT_ID, AUTHOR_ID, OTHER_MEMBER_ID);
    }

    /**
     * 받는 사람이 아니라 부모의 id 를 넘긴다. 여기서 부모를 읽으면 알림 때문에 하는 조회 하나가
     * 답글을 되돌린다 — 부모 읽기는 커밋 뒤에 도는 발송 쪽에 있다.
     */
    @Test
    void addReply_handsParentCommentIdToNotificationWithoutReadingIt() {
        givenPost(PostStatus.PUBLISHED);
        givenInsertedReply();

        communityCommentService.addReply(
                POST_ID, COMMENT_ID, commentFormOf("답글 본문"), AUTHOR_ID);

        verify(communityCommentNotificationService)
                .notifyNewReply(POST_ID, NEW_COMMENT_ID, COMMENT_ID, AUTHOR_ID);
        verify(communityCommentMapper, never()).findCommentById(anyLong());
    }

    @Test
    void addComment_savesContentWithAuthenticatedAuthor() {
        givenPost(PostStatus.PUBLISHED);
        givenInsertedComment(NEW_COMMENT_ID);

        communityCommentService.addComment(POST_ID, commentFormOf("댓글 본문"), AUTHOR_ID);

        Comment saved = capturedComment();
        assertThat(saved.getPostId()).isEqualTo(POST_ID);
        // 작성자는 인증 정보에서 가져온다.
        assertThat(saved.getMemberId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getContent()).isEqualTo("댓글 본문");
    }

    /** 삭제 글과 다른 작성자의 차단 글은 숨기고, 작성자에게만 차단을 알린다. */
    @ParameterizedTest(name = "{0} 글에 작성자={1}이 댓글을 달면 {2}")
    @CsvSource({
            "DELETED, true,  POST_NOT_FOUND",
            "BLOCKED, true,  BLOCKED_POST",
            "BLOCKED, false, POST_NOT_FOUND"
    })
    void addComment_notCommentablePost_isRejected(
            PostStatus status, boolean asAuthor, CommunityErrorCode expected) {
        givenPost(status);

        assertThatThrownBy(() -> communityCommentService.addComment(
                POST_ID, commentFormOf("댓글"), memberIdOf(asAuthor)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);

        verify(communityCommentMapper, never()).insertComment(any());
    }

    @Test
    void deleteComment_author_softDeletesComment() {
        givenPost(PostStatus.PUBLISHED);
        givenComment(AUTHOR_ID, CommentStatus.PUBLISHED);
        when(communityCommentMapper.deleteComment(COMMENT_ID, POST_ID, AUTHOR_ID)).thenReturn(1);

        communityCommentService.deleteComment(POST_ID, COMMENT_ID, AUTHOR_ID);

        verify(communityCommentMapper).deleteComment(COMMENT_ID, POST_ID, AUTHOR_ID);
    }

    /** 다른 작성자의 댓글, 다른 글의 댓글과 이미 삭제된 댓글은 모두 같은 오류로 숨긴다. */
    @ParameterizedTest(name = "같은 글={0}, 본인 댓글={1}, 상태={2}")
    @CsvSource({
            "true,  false, PUBLISHED",
            "false, true,  PUBLISHED",
            "true,  true,  DELETED"
    })
    void deleteComment_notOwnDeletableComment_isNotFound(
            boolean onSamePost, boolean ownComment, CommentStatus status) {
        givenPost(PostStatus.PUBLISHED);
        when(communityCommentMapper.findCommentById(COMMENT_ID)).thenReturn(commentOf(
                COMMENT_ID,
                onSamePost ? POST_ID : OTHER_POST_ID,
                memberIdOf(ownComment),
                status));

        assertThatThrownBy(
                () -> communityCommentService.deleteComment(POST_ID, COMMENT_ID, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND);

        verify(communityCommentMapper, never()).deleteComment(anyLong(), anyLong(), anyLong());
    }

    /** 조건부 댓글 삭제가 0행이면 성공으로 처리하지 않고, 경합 시점의 글 상태로 실패를 구분한다. */
    @ParameterizedTest(name = "0행 삭제 후 글이 {0}이면 {1}")
    @CsvSource({
            "PUBLISHED, COMMENT_NOT_FOUND",
            "BLOCKED,   BLOCKED_POST"
    })
    void deleteComment_whenNothingWasDeleted_isRejectedByCurrentStatus(
            PostStatus statusAfterDelete, CommunityErrorCode expected) {
        givenPost(PostStatus.PUBLISHED);
        givenComment(AUTHOR_ID, CommentStatus.PUBLISHED);
        when(communityCommentMapper.deleteComment(COMMENT_ID, POST_ID, AUTHOR_ID))
                .thenAnswer(invocation -> {
                    givenPost(statusAfterDelete);
                    return 0;
                });

        assertThatThrownBy(
                () -> communityCommentService.deleteComment(POST_ID, COMMENT_ID, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }

    private void givenAuthors(MemberCommunityView... authors) {
        when(memberCommunityQueryService.getMembersByIds(anyList()))
                .thenReturn(List.of(authors));
    }

    private CommentForm commentFormOf(String content) {
        CommentForm form = new CommentForm();
        form.setContent(content);

        return form;
    }

    private Comment capturedComment() {
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(communityCommentMapper).insertComment(captor.capture());

        return captor.getValue();
    }

    private Comment capturedReply() {
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(communityCommentMapper).insertReply(captor.capture());

        return captor.getValue();
    }

    /** 매퍼가 생성 키를 채우는 것을 흉내 낸다 — 알림이 그 id 로 간다. */
    private void givenInsertedComment(long newId) {
        when(communityCommentMapper.insertComment(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(newId);

            return 1;
        });
    }

    private void givenInsertedReply() {
        when(communityCommentMapper.insertReply(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Comment.class).setId(NEW_COMMENT_ID);

            return 1;
        });
    }

    private void givenComments(CommentRow... comments) {
        when(communityCommentMapper.findRecentRootComments(anyLong(), anyInt()))
                .thenReturn(List.of(comments));
        when(communityCommentMapper.countComments(POST_ID))
                .thenReturn(new CommentCountRow(comments.length, comments.length));
    }

    private void givenComment(long authorId, CommentStatus status) {
        when(communityCommentMapper.findCommentById(COMMENT_ID))
                .thenReturn(commentOf(COMMENT_ID, POST_ID, authorId, status));
    }

    private CommentRow commentOf(long commentId, CommentStatus status) {
        return commentOf(commentId, POST_ID, AUTHOR_ID, status);
    }

    private CommentRow commentOf(
            long commentId, long postId, long authorId, CommentStatus status) {
        return new CommentRow(
                commentId,
                postId,
                authorId,
                status == CommentStatus.DELETED ? null : "댓글 본문",
                status,
                CREATED_AT
        );
    }

    private CommentRow replyOf(long commentId, long parentCommentId, CommentStatus status) {
        return new CommentRow(
                commentId,
                POST_ID,
                AUTHOR_ID,
                parentCommentId,
                status == CommentStatus.DELETED ? null : "답글 본문",
                status,
                CREATED_AT
        );
    }

    /** status가 null이면 없는 글로 둔다. */
    private void givenPost(PostStatus status) {
        when(communityMapper.findPostById(POST_ID))
                .thenReturn(status == null ? null : postRowOf(status));
    }

    private PostDetailRow postRowOf(PostStatus status) {
        return new PostDetailRow(
                POST_ID,
                AUTHOR_ID,
                1L,
                "질문",
                "제목",
                "본문",
                status,
                status == PostStatus.BLOCKED ? "광고성 게시물" : null,
                10L,
                2L,
                CREATED_AT,
                CREATED_AT
        );
    }

    /** 상태 표의 "작성자" 열을 실제 회원 ID로 바꾼다. */
    private static long memberIdOf(boolean asAuthor) {
        return asAuthor ? AUTHOR_ID : OTHER_MEMBER_ID;
    }
}
