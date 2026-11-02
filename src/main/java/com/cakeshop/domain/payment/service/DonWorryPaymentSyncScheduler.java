package com.cakeshop.domain.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-11-02
 * 기능 : 돈워리 결제 조회 배치
 * 설명 : 웹훅이 오지 않거나 늦어도 최근 돈워리 결제의 승인·거절(VOIDED)을 주기적으로 맞춘다.
 * ******************************
 */
@Component
@ConditionalOnProperty(name = "donworry.payment.enabled", havingValue = "true")
public class DonWorryPaymentSyncScheduler {

    private final DonWorryPaymentSyncService syncService;
    private final int batchSize;

    public DonWorryPaymentSyncScheduler(
            DonWorryPaymentSyncService syncService,
            @Value("${donworry.payment.sync.batch-size:50}") int batchSize
    ) {
        this.syncService = syncService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${donworry.payment.sync.fixed-delay:60s}",
            initialDelayString = "${donworry.payment.sync.initial-delay:60s}"
    )
    public void syncRecentPayments() {
        syncService.syncRecent(batchSize);
    }
}
