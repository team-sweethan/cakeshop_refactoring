package com.cakeshop.domain;

import com.cakeshop.domain.order.entity.Order;
import com.cakeshop.domain.order.entity.OrderItem;
import com.cakeshop.domain.order.entity.OrderItemImage;
import com.cakeshop.domain.order.entity.OrderItemOption;
import com.cakeshop.domain.order.entity.OrderStatus;
import com.cakeshop.domain.order.entity.OrderType;
import com.cakeshop.domain.payment.entity.Payment;
import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.entity.PaymentCancellation;
import com.cakeshop.domain.payment.entity.PaymentCancellationStatus;
import com.cakeshop.domain.payment.entity.PaymentStatus;
import com.cakeshop.domain.product.entity.ProductType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OrderPaymentEntitySchemaTests {

    @Test
    void enumValuesMatchV2CheckConstraints() {
        assertThat(OrderType.values()).containsExactly(
                OrderType.GENERAL,
                OrderType.CUSTOM
        );
        assertThat(OrderStatus.values()).containsExactly(
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.UNDER_REVIEW,
                OrderStatus.IN_PRODUCTION,
                OrderStatus.READY_FOR_PICKUP,
                OrderStatus.PICKED_UP,
                OrderStatus.CANCELED,
                OrderStatus.REJECTED,
                OrderStatus.EXPIRED
        );
        assertThat(PaymentStatus.values()).containsExactly(
                PaymentStatus.READY,
                PaymentStatus.DONE,
                PaymentStatus.CANCELED,
                PaymentStatus.PARTIAL_CANCELED,
                PaymentStatus.ABORTED,
                PaymentStatus.EXPIRED
        );
        assertThat(PaymentCancellationStatus.values()).containsExactly(
                PaymentCancellationStatus.REQUESTED,
                PaymentCancellationStatus.DONE,
                PaymentCancellationStatus.FAILED
        );
    }

    @Test
    void orderFieldsMatchV2OrdersColumns() {
        assertFields(Order.class, Map.ofEntries(
                Map.entry("id", Long.class),
                Map.entry("orderNumber", String.class),
                Map.entry("memberId", Long.class),
                Map.entry("requestKey", String.class),
                Map.entry("orderType", OrderType.class),
                Map.entry("ordererName", String.class),
                Map.entry("ordererPhone", String.class),
                Map.entry("pickupName", String.class),
                Map.entry("pickupPhone", String.class),
                Map.entry("originalAmount", BigDecimal.class),
                Map.entry("discountAmount", BigDecimal.class),
                Map.entry("finalAmount", BigDecimal.class),
                Map.entry("status", OrderStatus.class),
                Map.entry("pickupAt", LocalDateTime.class),
                Map.entry("paymentExpiresAt", LocalDateTime.class),
                Map.entry("requestMessage", String.class),
                Map.entry("rejectReason", String.class),
                Map.entry("underReviewAt", LocalDateTime.class),
                Map.entry("approvedAt", LocalDateTime.class),
                Map.entry("rejectedAt", LocalDateTime.class),
                Map.entry("rejectedBy", Long.class),
                Map.entry("readyAt", LocalDateTime.class),
                Map.entry("approvedBy", Long.class),
                Map.entry("pickedUpAt", LocalDateTime.class),
                Map.entry("pickedUpBy", Long.class),
                Map.entry("expiredAt", LocalDateTime.class),
                Map.entry("canceledAt", LocalDateTime.class),
                Map.entry("cancelReason", String.class),
                Map.entry("canceledBy", String.class),
                Map.entry("pickupReminderSentAt", LocalDateTime.class),
                Map.entry("createdAt", LocalDateTime.class),
                Map.entry("updatedAt", LocalDateTime.class)
        ));
    }

    @Test
    void orderItemFieldsMatchV2OrderItemTables() {
        assertFields(OrderItem.class, Map.ofEntries(
                Map.entry("id", Long.class),
                Map.entry("orderId", Long.class),
                Map.entry("productId", Long.class),
                Map.entry("productName", String.class),
                Map.entry("productType", ProductType.class),
                Map.entry("quantity", Integer.class),
                Map.entry("basePrice", BigDecimal.class),
                Map.entry("optionAmount", BigDecimal.class),
                Map.entry("totalAmount", BigDecimal.class),
                Map.entry("requirements", String.class),
                Map.entry("preparationDays", Integer.class),
                Map.entry("stockDeductedAt", LocalDateTime.class),
                Map.entry("stockRestoredAt", LocalDateTime.class)
        ));
        assertFields(OrderItemOption.class, Map.ofEntries(
                Map.entry("id", Long.class),
                Map.entry("orderItemId", Long.class),
                Map.entry("productOptionId", Long.class),
                Map.entry("optionGroupName", String.class),
                Map.entry("optionName", String.class),
                Map.entry("additionalPrice", BigDecimal.class)
        ));
        assertFields(OrderItemImage.class, Map.ofEntries(
                Map.entry("id", Long.class),
                Map.entry("orderItemId", Long.class),
                Map.entry("imageUrl", String.class),
                Map.entry("sortOrder", Integer.class)
        ));
    }

    @Test
    void paymentFieldsMatchV2PaymentTables() {
        assertFields(Payment.class, Map.ofEntries(
                Map.entry("provider", PaymentProvider.class),
                Map.entry("id", Long.class),
                Map.entry("orderId", Long.class),
                Map.entry("gatewayOrderId", String.class),
                Map.entry("paymentKey", String.class),
                Map.entry("idempotencyKey", String.class),
                Map.entry("method", String.class),
                Map.entry("amount", BigDecimal.class),
                Map.entry("status", PaymentStatus.class),
                Map.entry("activePaymentOrderId", Long.class),
                Map.entry("failureCode", String.class),
                Map.entry("requestedAt", LocalDateTime.class),
                Map.entry("approvedAt", LocalDateTime.class),
                Map.entry("canceledAt", LocalDateTime.class),
                Map.entry("createdAt", LocalDateTime.class),
                Map.entry("updatedAt", LocalDateTime.class)
        ));
        assertFields(PaymentCancellation.class, Map.ofEntries(
                Map.entry("id", Long.class),
                Map.entry("paymentId", Long.class),
                Map.entry("idempotencyKey", String.class),
                Map.entry("cancelAmount", BigDecimal.class),
                Map.entry("cancelReason", String.class),
                Map.entry("requestType", String.class),
                Map.entry("requestedBy", Long.class),
                Map.entry("status", PaymentCancellationStatus.class),
                Map.entry("activeRequestedPaymentId", Long.class),
                Map.entry("transactionKey", String.class),
                Map.entry("failureCode", String.class),
                Map.entry("requestedAt", LocalDateTime.class),
                Map.entry("canceledAt", LocalDateTime.class),
                Map.entry("createdAt", LocalDateTime.class),
                Map.entry("updatedAt", LocalDateTime.class)
        ));
    }

    private void assertFields(Class<?> type, Map<String, Class<?>> expectedFields) {
        Map<String, Class<?>> actualFields = Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .collect(Collectors.toMap(Field::getName, Field::getType));

        assertThat(actualFields).containsExactlyInAnyOrderEntriesOf(expectedFields);
    }
}
