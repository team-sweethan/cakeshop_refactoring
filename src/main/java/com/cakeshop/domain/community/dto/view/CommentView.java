package com.cakeshop.domain.community.dto.view;

import com.cakeshop.domain.community.dto.query.CommentRow;
import com.cakeshop.domain.community.entity.CommentStatus;
import com.cakeshop.domain.member.dto.view.MemberCommunityView;

import java.time.LocalDateTime;

/**
 * ******************************
 * 작성자 : HyunGyu-Cho
 * 담당자 : 현규
 * 작성일 : 2026-08-05
 * 기능 : 커뮤니티 화면 데이터 전달
 * 설명 : CommentView 화면에 전달할 데이터를 정의한다.
 * ******************************
 */
// 댓글 한 줄(답글도 같은 타입이다). 상세 화면의 댓글 영역이 쓰고, 알림 문구도 여기 이름 규칙을 빌려 쓴다.
// 삭제된 댓글도 지우지 않고 status = DELETED 로 실어 보낸다 — 답글이 매달린 자리 표시가 필요해서다.
public record CommentView(
        Long id,
        Long postId,
        Long memberId,
        String authorNickname,
        boolean authorWithdrawn,
        // 매장 관리자가 쓴 댓글인지. 화면이 관리자 답변에 매장 배지를 붙인다.
        boolean authorAdmin,
        String content,
        CommentStatus status,
        LocalDateTime createdAt
) {

    // CommentRow(댓글 테이블에서 읽은 한 줄) + 작성자 -> 화면용 한 줄로 합치는 정적 팩토리다.
    // author = null 을 탈퇴로 보는 처리는 PostListView.of 와 같다. 탈퇴한 계정에는 배지를 붙이지 않는다.
    public static CommentView of(CommentRow row, MemberCommunityView author) {
        return new CommentView(
                row.id(),
                row.postId(),
                row.memberId(),
                author == null ? null : author.nickname(),
                author == null || author.withdrawn(),
                author != null && author.admin() && !author.withdrawn(),
                row.content(),
                row.status(),
                row.createdAt()
        );
    }

    // CommentView 를 만들지 않는 자리(알림 문구 등)가 이름만 필요할 때 부르는 입구다.
    // 회원 행을 못 찾은 경우(null)도 탈퇴와 똑같이 다뤄야 같은 사람이 화면과 알림에서 같은 이름으로 나온다.
    public static String authorNameOf(MemberCommunityView author) {
        return author == null
                ? authorNameOf(null, true)
                : authorNameOf(author.nickname(), author.withdrawn());
    }

    public String authorName() {
        return authorNameOf(authorNickname, authorWithdrawn);
    }

    // 화면이 "삭제된 댓글입니다" 자리 표시로 바꿔 그릴지 판단한다.
    public boolean isDeleted() {
        return status == CommentStatus.DELETED;
    }

    // 위의 두 입구가 공통으로 쓰는 실제 규칙 한 줄. 매개변수 목록이 달라 이름은 같아도 다른 메서드다(오버로딩).
    private static String authorNameOf(String nickname, boolean withdrawn) {
        return withdrawn ? PostListView.WITHDRAWN_AUTHOR_NAME : nickname;
    }
}
