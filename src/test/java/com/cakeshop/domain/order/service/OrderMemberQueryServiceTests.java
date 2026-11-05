package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.order.dto.view.OrderMemberOrderRow;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.mapper.OrderMemberMapper;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.error.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderMemberQueryServiceTests {

    @Mock
    private OrderMemberMapper orderMemberMapper;

    @InjectMocks
    private OrderMemberQueryService orderMemberQueryService;

    @Test
    void getMyPageOrders_authenticatedMember_returnsFiveItemGroupsWithOrderLabels() {
        when(orderMemberMapper.findInProgressOrders(1L, 5))
                .thenReturn(List.of(row(10L, OrderStatus.IN_PRODUCTION)));
        when(orderMemberMapper.findCompletedOrders(1L, 5))
                .thenReturn(List.of(row(20L, OrderStatus.PICKED_UP)));
        when(orderMemberMapper.countInProgressOrders(1L)).thenReturn(7L);

        var result = orderMemberQueryService.getMyPageOrders(1L);

        // 목록은 최근 5건까지만 담지만 요약 숫자는 진행 중 주문 전체 건수다.
        assertThat(result.inProgressCount()).isEqualTo(7L);

        assertThat(result.inProgressOrders())
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.orderId()).isEqualTo(10L);
                    assertThat(order.orderTypeLabel()).isEqualTo("주문 제작");
                    assertThat(order.statusLabel()).isEqualTo("제작 중");
                });
        assertThat(result.completedOrders())
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.orderId()).isEqualTo(20L);
                    assertThat(order.orderTypeLabel()).isEqualTo("일반 상품");
                    assertThat(order.statusLabel()).isEqualTo("픽업 완료");
                });
        verify(orderMemberMapper).findInProgressOrders(1L, 5);
        verify(orderMemberMapper).findCompletedOrders(1L, 5);
    }

    @Test
    void getMyPageOrders_invalidMemberId_throwsMemberNotAvailable() {
        assertThatThrownBy(() -> orderMemberQueryService.getMyPageOrders(0L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(OrderErrorCode.MEMBER_NOT_AVAILABLE));
    }

    @Test
    void getAdminMemberOrders_outOfRangePage_returnsLastPageWithLabels() {
        PageRequest pageRequest = new PageRequest(99, 10);
        when(orderMemberMapper.countOrdersByMemberId(1L)).thenReturn(12L);
        when(orderMemberMapper.findOrdersByMemberId(1L, 10, 10))
                .thenReturn(List.of(row(30L, OrderStatus.CANCELED)));

        var result = orderMemberQueryService.getAdminMemberOrders(1L, pageRequest);

        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(12);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getContent())
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.orderId()).isEqualTo(30L);
                    assertThat(order.orderTypeLabel()).isEqualTo("일반 상품");
                    assertThat(order.statusLabel()).isEqualTo("취소 완료");
                });
    }

    @Test
    void getAdminMemberOrders_noOrders_returnsEmptyPage() {
        PageRequest pageRequest = new PageRequest(99, 10);
        when(orderMemberMapper.countOrdersByMemberId(1L)).thenReturn(0L);

        var result = orderMemberQueryService.getAdminMemberOrders(1L, pageRequest);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getTotalElements()).isZero();
    }

    private OrderMemberOrderRow row(long orderId, OrderStatus status) {
        return new OrderMemberOrderRow(
                orderId,
                "ORD-" + orderId,
                status == OrderStatus.IN_PRODUCTION ? OrderType.CUSTOM : OrderType.GENERAL,
                "생크림 케이크",
                2,
                status,
                BigDecimal.valueOf(35000),
                LocalDateTime.of(2026, 8, 17, 14, 0),
                LocalDateTime.of(2026, 8, 10, 11, 30)
        );
    }
}
