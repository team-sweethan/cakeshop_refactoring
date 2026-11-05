package com.cakeshop.domain.member.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.cakeshop.domain.member.dto.view.MemberCommunityView;
import com.cakeshop.domain.member.entity.MemberStatus;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ******************************
 * 작성자 : HyunGyu-Cho
 * 담당자 : 수민
 * 작성일 : 2026-08-07
 * 기능 : 커뮤니티 작성자 표기용 회원 조회 SQL 검증
 * 설명 : 탈퇴 판정과 없는 ID 처리를 MariaDB Testcontainers 로 고정한다.
 * ******************************
 */
@MybatisTest
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MemberCommunityMapperTests {

    @Autowired
    private MemberCommunityMapper memberCommunityMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findMembersByIds_withdrawnMember_keepsNicknameAndMarksWithdrawn() {
        Long activeId = insertMember("활동회원", MemberStatus.ACTIVE);
        Long withdrawnId = insertMember("탈퇴회원", MemberStatus.WITHDRAWN);

        List<MemberCommunityView> members =
                memberCommunityMapper.findMembersByIds(List.of(activeId, withdrawnId));

        assertThat(members)
                .extracting(
                        MemberCommunityView::id,
                        MemberCommunityView::nickname,
                        MemberCommunityView::withdrawn)
                .containsExactlyInAnyOrder(
                        tuple(activeId, "활동회원", false),
                        tuple(withdrawnId, "탈퇴회원", true));
    }

    /** SUSPENDED가 탈퇴로 새면 정지 회원의 글이 화면에서 "탈퇴한 회원"으로 표기된다. */
    @Test
    void findMembersByIds_suspendedMember_isNotMarkedWithdrawn() {
        Long suspendedId = insertMember("정지회원", MemberStatus.SUSPENDED);

        List<MemberCommunityView> members =
                memberCommunityMapper.findMembersByIds(List.of(suspendedId));

        assertThat(members).singleElement()
                .satisfies(member -> {
                    assertThat(member.nickname()).isEqualTo("정지회원");
                    assertThat(member.withdrawn()).isFalse();
                });
    }

    /** 없는 회원 ID가 섞여도 목록 조회 전체가 실패하지 않아야 한다. */
    @Test
    void findMembersByIds_missingId_isOmittedWithoutError() {
        Long existingId = insertMember("남은회원", MemberStatus.ACTIVE);

        List<MemberCommunityView> members =
                memberCommunityMapper.findMembersByIds(List.of(existingId, Long.MAX_VALUE));

        assertThat(members).extracting(MemberCommunityView::id)
                .containsExactly(existingId);
    }

    /** 관리자 계정만 admin 이 참이다. 댓글의 매장 배지가 이 값을 그대로 쓴다. */
    @Test
    void findMembersByIds_adminRole_isMarkedAdmin() {
        Long memberId = insertMember("일반회원", MemberStatus.ACTIVE);
        Long adminId = insertMember("매장관리자", MemberStatus.ACTIVE, "ADMIN");

        List<MemberCommunityView> members =
                memberCommunityMapper.findMembersByIds(List.of(memberId, adminId));

        assertThat(members)
                .extracting(MemberCommunityView::id, MemberCommunityView::admin)
                .containsExactlyInAnyOrder(
                        tuple(memberId, false),
                        tuple(adminId, true));
    }

    private Long insertMember(String nickname, MemberStatus status) {
        return insertMember(nickname, status, "USER");
    }

    /**
     * 실명과 닉네임을 <b>다른 값</b>으로 넣는다. 같은 값이면 이 매퍼가 {@code nickname} 대신
     * {@code name}을 조회하도록 바뀌어도 단언이 전부 통과하는데, 화면에 실명이 뜨는 회귀라
     * 개인정보 문제다 (PR #144 Codex 리뷰).
     */
    private Long insertMember(String nickname, MemberStatus status, String role) {
        String email = nickname + "-" + System.nanoTime() + "@example.com";
        jdbcTemplate.update(
                """
                INSERT INTO members (email, password, name, nickname, phone, role, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                email,
                "encoded-password",
                nickname + "실명",
                nickname,
                "010-0000-0000",
                role,
                status.name()
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?", Long.class, email);
    }
}
