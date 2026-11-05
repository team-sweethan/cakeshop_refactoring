package com.cakeshop.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.cakeshop.domain.community.dto.query.CommentRow;
import com.cakeshop.domain.community.entity.CommentStatus;
import com.cakeshop.domain.community.mapper.CommunityCommentMapper;
import com.cakeshop.domain.member.dto.view.MemberCommunityView;
import com.cakeshop.domain.notification.dto.form.NotificationRequest;
import com.cakeshop.domain.notification.entity.NotificationType;
import com.cakeshop.domain.notification.service.NotificationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 커밋 후 답글 수신자 조회와 자기 행위 억제를 발송 계층에서 확인한다. */
class CommunityCommentNotificationSenderTests {

    private static final long POST_ID = 42L;
    private static final long REPLY_ID = 314L;
    private static final long PARENT_COMMENT_ID = 313L;
    private static final long PARENT_AUTHOR_ID = 7L;
    private static final long ACTOR_ID = 99L;

    private NotificationService notificationService;
    private CommunityMemberViewLoader memberViewLoader;
    private CommunityCommentMapper commentMapper;
    private CommunityCommentNotificationSender sender;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        memberViewLoader = mock(CommunityMemberViewLoader.class);
        commentMapper = mock(CommunityCommentMapper.class);
        sender = new CommunityCommentNotificationSender(
                notificationService, memberViewLoader, commentMapper);
    }

    @Test
    void sendNewReply_readsParentAuthorAndSendsToThatMember() {
        when(commentMapper.findCommentById(PARENT_COMMENT_ID))
                .thenReturn(parentComment(PARENT_AUTHOR_ID));
        when(memberViewLoader.findByIds(any()))
                .thenReturn(Map.of(ACTOR_ID, new MemberCommunityView(ACTOR_ID, "답글쓴이", false, false)));

        sender.sendNewReply(POST_ID, REPLY_ID, PARENT_COMMENT_ID, ACTOR_ID);

        ArgumentCaptor<NotificationRequest> request =
                ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).makeNotification(request.capture());
        assertThat(request.getValue().getReceiverId()).isEqualTo(PARENT_AUTHOR_ID);
        assertThat(request.getValue().getCommentId()).isEqualTo(REPLY_ID);
        assertThat(request.getValue().getType()).isEqualTo(NotificationType.CUSTOMER_COMMENT_REPLY);
        assertThat(request.getValue().getEventKey())
                .isEqualTo("CUSTOMER_COMMENT_REPLY:" + PARENT_AUTHOR_ID + ":" + REPLY_ID);
    }

    @Test
    void sendNewReply_byParentAuthor_sendsNothing() {
        when(commentMapper.findCommentById(PARENT_COMMENT_ID))
                .thenReturn(parentComment(ACTOR_ID));

        sender.sendNewReply(POST_ID, REPLY_ID, PARENT_COMMENT_ID, ACTOR_ID);

        verifyNoInteractions(notificationService, memberViewLoader);
    }

    @Test
    void sendNewReply_withoutParent_sendsNothing() {
        sender.sendNewReply(POST_ID, REPLY_ID, PARENT_COMMENT_ID, ACTOR_ID);

        verifyNoInteractions(notificationService, memberViewLoader);
    }

    private CommentRow parentComment(long memberId) {
        return new CommentRow(
                PARENT_COMMENT_ID,
                POST_ID,
                memberId,
                "부모 댓글",
                CommentStatus.PUBLISHED,
                null);
    }
}
