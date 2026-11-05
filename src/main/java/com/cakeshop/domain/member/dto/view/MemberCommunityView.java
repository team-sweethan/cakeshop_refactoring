package com.cakeshop.domain.member.dto.view;

/**
 * ******************************
 * 작성자 : HyunGyu-Cho
 * 담당자 : 수민
 * 작성일 : 2026-08-07
 * 기능 : 커뮤니티 작성자 표기용 회원 조회 계약
 * 설명 : 커뮤니티가 members 를 직접 JOIN 하지 않도록 작성자 표기에 필요한 최소 필드만 제공한다.
 * ******************************
 *
 * <p>탈퇴 판정은 회원 도메인의 업무 규칙이므로 {@code MemberStatus} 를 그대로 노출하지 않고
 * {@code withdrawn} 으로 계산해 넘긴다. 커뮤니티가 회원 상태 모델을 알 필요가 없다.</p>
 *
 * <p>탈퇴 회원의 닉네임도 그대로 반환한다. "탈퇴한 회원" 표기는 화면의 일이라 커뮤니티 View 가 맡는다.</p>
 *
 * <p>{@code admin} 은 매장 관리자 계정인지다. 커뮤니티 댓글에서 관리자 답변에 매장 배지를 붙이는 데 쓴다.
 * 역할 모델({@code MemberRole})을 그대로 넘기지 않는 이유는 {@code withdrawn} 과 같다.</p>
 */
public record MemberCommunityView(
        Long id,
        String nickname,
        boolean withdrawn,
        boolean admin
) {
}
