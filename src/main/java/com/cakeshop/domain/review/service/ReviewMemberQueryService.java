package com.cakeshop.domain.review.service;

import com.cakeshop.global.common.paging.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 현규
 * 작성일 : 2026-11-05
 * 기능 : 회원 마이페이지 후기 요약 조회
 * 설명 : member 도메인 마이페이지의 "작성할 후기" 건수만 제공한다.
 *        작성 가능 여부 판단은 후기 화면(작성할 후기 목록)과 같은 ReviewService 규칙을 그대로 쓴다.
 * ******************************
 */
@Service
@RequiredArgsConstructor
public class ReviewMemberQueryService {

    private final ReviewService reviewService;

    /** 아직 후기를 쓰지 않은 주문 상품 수. 작성할 후기 목록의 전체 건수와 같다. */
    @Transactional(readOnly = true)
    public long countWritableOrderItems(long memberId) {
        return reviewService.getWritableOrderItems(memberId, new PageRequest(1, 1))
                .getTotalElements();
    }
}
