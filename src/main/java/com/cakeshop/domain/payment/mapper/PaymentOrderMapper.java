package com.cakeshop.domain.payment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-04
 * 기능 : 주문 도메인이 쓰는 결제 조회 전용 Mapper
 * 설명 : PaymentOrderQueryService 전용이다. payments 테이블과 결제 판정 규칙은 결제 도메인 안에만 둔다.
 * ******************************
 */
@Mapper
public interface PaymentOrderMapper {

    /** PG(돈워리)가 주문의 완료 결제를 아직 확인 중인지(매입 보류, 판정 전) */
    boolean existsPaymentUnderGatewayReview(@Param("orderId") long orderId);
}
