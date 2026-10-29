package com.cakeshop.domain.order.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

import com.cakeshop.domain.order.dto.DonWorryPickupEvent;
import com.cakeshop.domain.order.dto.DonWorryPickupMessage;
import com.cakeshop.domain.order.infra.DonWorryPickupClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class DonWorryPickupSenderTests {
    @Test
    void sendsAfterCommitButNeverOnRollbackOrOutsideTransaction() {
        try (var context = context()) {
            var client = context.getBean(DonWorryPickupClient.class);
            var query = context.getBean(DonWorryPickupQueryService.class);
            var message = new DonWorryPickupMessage("ORD-1", Instant.EPOCH, null, List.of());
            when(query.getMessage(1, false)).thenReturn(message);
            var transaction = new TransactionTemplate(context.getBean(TestTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new DonWorryPickupEvent(1, false));
                verifyNoInteractions(client, query);
                status.setRollbackOnly();
            });
            context.publishEvent(new DonWorryPickupEvent(1, false));
            verifyNoInteractions(client, query);
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new DonWorryPickupEvent(1, false));
                verifyNoInteractions(client, query);
            });
            verify(client).send(message, false);
        }
    }

    @Test
    void deliveryFailureDoesNotTurnCommittedOrderIntoAnError() {
        try (var context = context()) {
            var query = context.getBean(DonWorryPickupQueryService.class);
            doThrow(new IllegalStateException("unavailable")).when(query).getMessage(2, true);
            var transaction = new TransactionTemplate(context.getBean(TestTransactionManager.class));
            assertThatCode(() -> transaction.executeWithoutResult(status ->
                    context.publishEvent(new DonWorryPickupEvent(2, true)))).doesNotThrowAnyException();
            verifyNoInteractions(context.getBean(DonWorryPickupClient.class));
        }
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                java.util.Map.of("donworry.pickup.enabled", "true")));
        context.register(TestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(DonWorryPickupSender.class)
    static class TestConfig {
        @Bean DonWorryPickupQueryService query() { return mock(DonWorryPickupQueryService.class); }
        @Bean DonWorryPickupClient client() { return mock(DonWorryPickupClient.class); }
        @Bean TestTransactionManager transactionManager() { return new TestTransactionManager(); }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
