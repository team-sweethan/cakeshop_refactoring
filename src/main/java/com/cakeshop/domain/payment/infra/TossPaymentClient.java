package com.cakeshop.domain.payment.infra;

import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

// 토스 승인·취소·조회 API 클라이언트 — 작업별 UUID를 Idempotency-Key로 사용
@Component
public class TossPaymentClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient restClient;
    private final String authorization;

    @Autowired
    public TossPaymentClient(
            @Value("${app.payment.toss.base-url}") String baseUrl,
            @Value("${app.payment.toss.secret-key:}") String secretKey,
            @Value("${app.payment.toss.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app.payment.toss.read-timeout:5s}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(requirePositive(connectTimeout));
        requestFactory.setReadTimeout(requirePositive(readTimeout));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.authorization = createAuthorization(secretKey);
    }

    TossPaymentClient(RestClient restClient, String secretKey) {
        this.restClient = restClient;
        this.authorization = createAuthorization(secretKey);
    }

    /** Toss 결제 승인 API를 호출하고 내부 완료 처리에 필요한 결과만 반환한다. */
    public ApprovalResult approve(
            String paymentKey,
            String orderId,
            long amount,
            String idempotencyKey
    ) {
        if (authorization == null) {
            throw new BusinessException(
                    PaymentErrorCode.TOSS_APPROVAL_FAILED
            );
        }

        try {
            TossPaymentResponse response = restClient.post()
                    .uri("/v1/payments/confirm")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .header(IDEMPOTENCY_KEY, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossApprovalRequest(
                            paymentKey,
                            orderId,
                            amount
                    ))
                    .retrieve()
                    .body(TossPaymentResponse.class);

            if (response == null || response.approvedAt() == null) {
                throw new BusinessException(
                        PaymentErrorCode.TOSS_APPROVAL_FAILED
                );
            }

            return new ApprovalResult(
                    response.paymentKey(),
                    response.orderId(),
                    response.method(),
                    toGatewayStatus(response.status()),
                    response.totalAmount(),
                    response.approvedAt().toLocalDateTime()
            );
        } catch (RestClientException exception) {
            // PG 오류 응답의 상세 메시지나 인증 정보를 사용자에게 노출하지 않는다.
            throw new BusinessException(
                    PaymentErrorCode.TOSS_APPROVAL_FAILED
            );
        }
    }

    /** Toss 결제 전체 취소 API를 호출하고 내부 완료 처리에 필요한 결과를 반환한다. */
    public CancellationResult cancel(String paymentKey, String reason, String idempotencyKey) {
        if (authorization == null
                || paymentKey == null
                || paymentKey.isBlank()
                || reason == null
                || reason.isBlank()
                || idempotencyKey == null
                || idempotencyKey.isBlank()) {
            throw new BusinessException(PaymentErrorCode.TOSS_CANCEL_FAILED);
        }

        try {
            TossPaymentResponse response = restClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .header(IDEMPOTENCY_KEY, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossCancelRequest(reason))
                    .retrieve()
                    .body(TossPaymentResponse.class);

            if (response == null
                    || !"CANCELED".equals(response.status())
                    || response.cancels() == null
                    || response.cancels().isEmpty()) {
                throw new BusinessException(PaymentErrorCode.TOSS_CANCEL_FAILED);
            }
            TossCancellationResponse cancellation = response.cancels().getLast();
            if (cancellation.transactionKey() == null
                    || cancellation.transactionKey().isBlank()
                    || cancellation.canceledAt() == null) {
                throw new BusinessException(PaymentErrorCode.TOSS_CANCEL_FAILED);
            }
            return new CancellationResult(
                    toGatewayStatus(response.status()),
                    cancellation.transactionKey(),
                    cancellation.canceledAt().toLocalDateTime()
            );
        } catch (RestClientException exception) {
            throw new BusinessException(PaymentErrorCode.TOSS_CANCEL_FAILED);
        }
    }

    /** paymentKey로 Toss의 현재 결제 상태를 조회한다. */
    public Optional<PaymentLookupResult> find(String paymentKey) {
        if (authorization == null || paymentKey == null || paymentKey.isBlank()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
        }

        try {
            TossPaymentResponse response = restClient.get()
                    .uri("/v1/payments/{paymentKey}", paymentKey)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(TossPaymentResponse.class);
            if (response == null) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
            }
            return Optional.of(toLookupResult(response));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
        } catch (RestClientException exception) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
        }
    }

    private PaymentLookupResult toLookupResult(TossPaymentResponse response) {
        CancellationResult cancellation = null;
        if (response.cancels() != null && !response.cancels().isEmpty()) {
            TossCancellationResponse lastCancellation = response.cancels().getLast();
            if (lastCancellation.canceledAt() != null) {
                cancellation = new CancellationResult(
                        toGatewayStatus(response.status()),
                        lastCancellation.transactionKey(),
                        lastCancellation.canceledAt().toLocalDateTime()
                );
            }
        }

        return new PaymentLookupResult(
                response.paymentKey(),
                response.orderId(),
                response.method(),
                toGatewayStatus(response.status()),
                response.totalAmount(),
                response.approvedAt() == null
                        ? null
                        : response.approvedAt().toLocalDateTime(),
                cancellation
        );
    }

    private PaymentGatewayStatus toGatewayStatus(String status) {
        return switch (status) {
            case null -> null;
            case "READY" -> PaymentGatewayStatus.READY;
            case "IN_PROGRESS" -> PaymentGatewayStatus.IN_PROGRESS;
            case "DONE" -> PaymentGatewayStatus.DONE;
            case "CANCELED" -> PaymentGatewayStatus.CANCELED;
            case "ABORTED" -> PaymentGatewayStatus.ABORTED;
            case "EXPIRED" -> PaymentGatewayStatus.EXPIRED;
            default -> PaymentGatewayStatus.UNKNOWN;
        };
    }

    private Duration requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("Toss payment timeout must be positive.");
        }
        return timeout;
    }

    private String createAuthorization(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return null;
        }

        String credentials = Base64.getEncoder().encodeToString(
                (secretKey + ":").getBytes(StandardCharsets.UTF_8)
        );
        return "Basic " + credentials;
    }

    private record TossApprovalRequest(
            String paymentKey,
            String orderId,
            long amount
    ) {
    }

    private record TossCancelRequest(String cancelReason) {
    }

    private record TossPaymentResponse(
            String paymentKey,
            String orderId,
            String method,
            String status,
            long totalAmount,
            OffsetDateTime approvedAt,
            List<TossCancellationResponse> cancels
    ) {
    }

    private record TossCancellationResponse(
            String transactionKey,
            OffsetDateTime canceledAt
    ) {
    }
}
