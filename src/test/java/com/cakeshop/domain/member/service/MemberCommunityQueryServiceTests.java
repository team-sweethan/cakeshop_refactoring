package com.cakeshop.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.member.dto.view.MemberCommunityView;
import com.cakeshop.domain.member.mapper.MemberCommunityMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ******************************
 * 작성자 : HyunGyu-Cho
 * 담당자 : 수민
 * 작성일 : 2026-08-07
 * 기능 : 커뮤니티 작성자 표기용 회원 조회 계약 검증
 * 설명 : 빈 ID 목록이 Mapper 까지 내려가지 않는지를 고정한다.
 * ******************************
 */
@ExtendWith(MockitoExtension.class)
class MemberCommunityQueryServiceTests {

    @Mock
    private MemberCommunityMapper memberCommunityMapper;

    @InjectMocks
    private MemberCommunityQueryService memberCommunityQueryService;

    /** 빈 IN 절은 SQL 문법 오류가 되므로 Mapper까지 가지 않아야 한다. */
    @Test
    void getMembersByIds_emptyIds_returnsEmptyWithoutQuery() {
        assertThat(memberCommunityQueryService.getMembersByIds(List.of())).isEmpty();
        assertThat(memberCommunityQueryService.getMembersByIds(null)).isEmpty();

        verifyNoInteractions(memberCommunityMapper);
    }

    @Test
    void getMembersByIds_existingIds_returnsMapperResult() {
        MemberCommunityView view = new MemberCommunityView(1L, "작성자", false, false);
        when(memberCommunityMapper.findMembersByIds(List.of(1L))).thenReturn(List.of(view));

        assertThat(memberCommunityQueryService.getMembersByIds(List.of(1L)))
                .containsExactly(view);
    }
}
