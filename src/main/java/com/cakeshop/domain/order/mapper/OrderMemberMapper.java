package com.cakeshop.domain.order.mapper;

import com.cakeshop.domain.order.dto.view.OrderMemberOrderRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환
 * 작성일 : 2026-08-15
 * 기능 : 회원 화면 주문 조회 매핑
 * 설명 : 회원 도메인의 마이페이지와 관리자 회원 상세에 필요한 주문 조회만 제공한다.
 * ******************************
 */
@Mapper
public interface OrderMemberMapper {

    List<OrderMemberOrderRow> findInProgressOrders(
            @Param("memberId") long memberId,
            @Param("limit") int limit
    );

    List<OrderMemberOrderRow> findCompletedOrders(
            @Param("memberId") long memberId,
            @Param("limit") int limit
    );

    long countInProgressOrders(@Param("memberId") long memberId);

    long countOrdersByMemberId(@Param("memberId") long memberId);

    List<OrderMemberOrderRow> findOrdersByMemberId(
            @Param("memberId") long memberId,
            @Param("offset") int offset,
            @Param("size") int size
    );
}
