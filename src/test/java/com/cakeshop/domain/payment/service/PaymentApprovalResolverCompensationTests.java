package com.cakeshop.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.infra.CancellationResult;
import com.cakeshop.domain.payment.infra.PaymentGateway;
import com.cakeshop.domain.payment.infra.PaymentGatewayRouter;
import com.cakeshop.domain.payment.infra.PaymentGatewayStatus;
import com.cakeshop.domain.payment.infra.PaymentLookupResult;
import com.cakeshop.domain.payment.service.PaymentApprovalResolver.ApprovalState;
import com.cakeshop.domain.payment.service.PaymentRecoveryService.CompensationRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-04
 * 기능 : 보상 취소 복구의 PG 상태 해석 검증
 * 설명 : PG가 승인을 취소해 청구 없이 끝난 결제(돈워리 VOIDED)는 "승인 안 됨"이 아니라 취소 완료로 본다(#25 Codex 리뷰).
 *        승인 취소 기록이 없는 미승인 결과(Toss 등)는 지금처럼 승인 안 됨이다.
 * ******************************
 */
class PaymentApprovalResolverCompensationTests {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 4, 12, 0);

    private final PaymentGatewayRouter router = mock(PaymentGatewayRouter.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentApprovalResolver resolver = new PaymentApprovalResolver(router);
    private final CompensationRequest request = new CompensationRequest(
            PaymentProvider.OWN, 1L, 5L, "DW-101", "COMPENSATE-1", BigDecimal.valueOf(25_000), "자동 결제 취소");

    @Test
    void abortedWithGatewayCancellation_isCanceled() {
        when(router.forProvider(PaymentProvider.OWN)).thenReturn(gateway);
        when(gateway.find("DW-101")).thenReturn(Optional.of(lookup(
                new CancellationResult(PaymentGatewayStatus.CANCELED, "DW-VOID-101", AT))));

        var resolution = resolver.resolveCompensationState(request);

        assertThat(resolution.state()).isEqualTo(ApprovalState.CANCELED);
        assertThat(resolution.lookup().status()).isEqualTo(PaymentGatewayStatus.CANCELED);
        assertThat(resolution.lookup().cancellation().transactionKey()).isEqualTo("DW-VOID-101");
    }

    @Test
    void abortedWithoutCancellation_isNotApproved() {
        when(router.forProvider(PaymentProvider.OWN)).thenReturn(gateway);
        when(gateway.find("DW-101")).thenReturn(Optional.of(lookup(null)));

        assertThat(resolver.resolveCompensationState(request).state()).isEqualTo(ApprovalState.NOT_APPROVED);
    }

    private static PaymentLookupResult lookup(CancellationResult cancellation) {
        return new PaymentLookupResult(
                "DW-101", "ORD-100", "카드", PaymentGatewayStatus.ABORTED, 25_000L, AT, cancellation);
    }
}
