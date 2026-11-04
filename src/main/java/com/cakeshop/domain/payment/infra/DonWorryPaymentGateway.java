package com.cakeshop.domain.payment.infra;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry를 자체 PG(OWN)로 연결
 * 설명 : 돈워리 결제창(호스팅)이 카드 승인을 마치면 기존 성공 콜백으로 돌아온다(paymentKey = DW-돈워리 결제 ID).
 *        approve는 새 승인을 요청하지 않고 돈워리 상태를 조회해 승인 완료(authorized)인지 확인한다.
 *        paymentKey는 "DW-" + DonWorry paymentId다.
 *        cancel은 DonWorry 전액 취소 API(POST /api/payments/{paymentId}/cancellations, DonWorry #381)를 부른다.
 *        보류·매입 대기 결제는 승인 취소(#382), 매입된 결제의 환불은 DonWorry 환불 실행 연결 전까지 503이라
 *        취소 요청을 REQUESTED로 두고 복구 배치가 다시 보낸다(아래 cancel 설명).
 * ******************************
 */
@Component
@ConditionalOnProperty(name = "donworry.payment.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DonWorryPaymentGateway implements PaymentGateway {

    public static final String PAYMENT_KEY_PREFIX = "DW-";
    // 돈워리 결제창이 failUrl에 붙이는 code 값 (DonWorry ADR-0064 2절). 구매자가 결제창을 닫으면 USER_CANCELED,
    // 카드 거절이면 결제의 declineCode(AUTHORIZATION_DECLINED), 그 밖에는 결제 상태 이름이다.
    public static final String USER_CANCELED = "USER_CANCELED";
    public static final String AUTHORIZATION_DECLINED = "AUTHORIZATION_DECLINED";
    private static final int ORDER_NAME_MAX_LENGTH = 100;
    private static final String METHOD = "카드";

    private final DonWorryPaymentClient client;
    private final Clock clock;

    public static String paymentKey(long paymentId) {
        return PAYMENT_KEY_PREFIX + paymentId;
    }

    /** "DW-" 뒤의 양수 paymentId만 받는다. 형식이 다르면 비어 있다. */
    public static Optional<Long> paymentId(String paymentKey) {
        if (paymentKey == null || !paymentKey.matches("DW-[1-9][0-9]{0,18}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(paymentKey.substring(PAYMENT_KEY_PREFIX.length())));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.OWN;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String clientKey() {
        // DonWorry 결제창은 브라우저용 키를 쓰지 않는다.
        return "";
    }

    @Override
    public String formatOrderName(String orderName) {
        if (orderName.length() <= ORDER_NAME_MAX_LENGTH) {
            return orderName;
        }
        return orderName.substring(0, ORDER_NAME_MAX_LENGTH);
    }

    @Override
    public boolean isUserCanceled(String failureCode) {
        return USER_CANCELED.equals(failureCode);
    }

    @Override
    public boolean isCardRejected(String failureCode) {
        return AUTHORIZATION_DECLINED.equals(failureCode);
    }

    @Override
    public ApprovalResult approve(String paymentKey, String orderId, long amount, String idempotencyKey) {
        PaymentLookupResult lookup = find(paymentKey)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED));
        // 매입 전(PENDING·HELD)이나 거절된 결제는 승인으로 보지 않는다. 호출자가 조회로 상태를 다시 해석한다.
        if (lookup.status() != PaymentGatewayStatus.DONE) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_APPROVAL_FAILED);
        }
        return lookup.toApprovalResult();
    }

    @Override
    public Optional<PaymentLookupResult> find(String paymentKey) {
        Optional<Long> paymentId = paymentId(paymentKey);
        if (paymentId.isEmpty()) {
            return Optional.empty();
        }
        DonWorryPaymentResponse response;
        try {
            response = client.find(paymentId.get());
        } catch (DonWorryPaymentException exception) {
            if (exception.getStatus() == 404) {
                return Optional.empty();
            }
            throw new BusinessException(PaymentErrorCode.PAYMENT_STATUS_LOOKUP_FAILED);
        }
        return Optional.of(new PaymentLookupResult(
                paymentKey(response.paymentId()),
                response.orderRef(),
                METHOD,
                toGatewayStatus(response),
                response.amount(),
                response.statusChangedAt() == null
                        ? null
                        : LocalDateTime.ofInstant(response.statusChangedAt(), clock.getZone()),
                null
        ));
    }

    /**
     * 돈워리에 전액 취소를 요청한다. 완료(SUCCEEDED·ALREADY_CANCELED)만 결과로 돌려준다.
     * 결과 대기(PROCESSING·UNKNOWN)·진행 중(PAYMENT_IN_PROGRESS)·준비 안 됨(CANCELLATION_NOT_READY)·일시 장애는
     * PAYMENT_RECOVERY_PENDING으로 알려, CakeShop 취소 요청을 REQUESTED로 두고 복구 배치가 같은 키로 다시 보낸다.
     * 취소할 거래가 없음·확인 필요·키 충돌·실패 확정은 PAYMENT_CANCEL_FAILED다.
     * 보류(HELD) 결제는 승인 취소가 실제로 실행된다(DonWorry #382). 매입된(CAPTURED) 결제의 환불은 DonWorry #383으로
     * 기반만 들어가고 환불 실행 연결이 아직 없어 503 CANCELLATION_NOT_READY다(2026-11-04 DonWorry dev 기준).
     * 그동안 취소 요청이 REQUESTED로 남아 복구 배치가 다시 보낸다.
     */
    @Override
    public CancellationResult cancel(String paymentKey, String reason, String idempotencyKey) {
        long paymentId = paymentId(paymentKey)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED));
        DonWorryCancellationResponse response;
        try {
            response = client.cancel(paymentId, cancellationReason(reason), cancellationKey(idempotencyKey));
        } catch (DonWorryPaymentException exception) {
            throw new BusinessException(retryable(exception)
                    ? PaymentErrorCode.PAYMENT_RECOVERY_PENDING
                    : PaymentErrorCode.PAYMENT_CANCEL_FAILED);
        }
        if (!response.completed()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_RECOVERY_PENDING);
        }
        // 직원 거절(VOIDED)로 이미 취소된 결제는 고객 취소 작업 ID가 없을 수 있어 요청 키로 거래 키를 만든다.
        String transactionKey = response.cancellationId() != null
                ? "DW-CANCEL-" + response.cancellationId()
                : "DW-CANCEL-REQ-" + response.requestKey();
        LocalDateTime canceledAt = response.completedAt() != null
                ? LocalDateTime.ofInstant(response.completedAt(), clock.getZone())
                : LocalDateTime.now(clock);
        return new CancellationResult(PaymentGatewayStatus.CANCELED, transactionKey, canceledAt);
    }

    private static boolean retryable(DonWorryPaymentException exception) {
        int status = exception.getStatus();
        String code = exception.getCode();
        return status == 0
                || status >= 500
                || status == 429
                || "PAYMENT_IN_PROGRESS".equals(code)
                || "CANCELLATION_ALREADY_IN_PROGRESS".equals(code);
    }

    /** 돈워리는 UUID 키만 받는다. CakeShop 키(CANCEL-{uuid})는 UUID 부분을, 그 밖의 키는 같은 값에서 항상 같은 UUID를 쓴다. */
    static String cancellationKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
        }
        String candidate = idempotencyKey.startsWith("CANCEL-") ? idempotencyKey.substring(7) : idempotencyKey;
        try {
            UUID uuid = UUID.fromString(candidate);
            if (uuid.toString().equalsIgnoreCase(candidate)) {
                return uuid.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 이름 기반 UUID로 바꾼다.
        }
        return UUID.nameUUIDFromBytes(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** 돈워리 사유 규칙(앞뒤 공백 없음, 1~200자)에 맞춘다. 같은 입력은 항상 같은 사유가 되어 재요청 내용이 같다. */
    static String cancellationReason(String reason) {
        String value = reason == null ? "" : reason.strip();
        if (value.isEmpty()) {
            return "고객 요청으로 주문 취소";
        }
        if (value.codePointCount(0, value.length()) > 200) {
            value = value.substring(0, value.offsetByCodePoints(0, 200)).strip();
        }
        return value;
    }

    // 카드 승인이 끝났으면(매입 대기·보류 포함) 결제 완료로 본다. 실제 PG의 "승인 = 결제됨"과 같다.
    // 보류 뒤 직원 거절(VOIDED)을 주문에 반영하는 일은 돈워리 결제 상태 웹훅(DonWorry ADR-0064 3절)을 받는 후속 PR에서 한다.
    // 이 PR만으로는 거절돼도 주문이 결제 완료로 남는다.
    static PaymentGatewayStatus toGatewayStatus(DonWorryPaymentResponse response) {
        if (response.approved()) {
            return PaymentGatewayStatus.DONE;
        }
        return toGatewayStatus(response.paymentStatus());
    }

    static PaymentGatewayStatus toGatewayStatus(String donWorryStatus) {
        if (donWorryStatus == null) {
            return PaymentGatewayStatus.UNKNOWN;
        }
        return switch (donWorryStatus) {
            case "CAPTURED" -> PaymentGatewayStatus.DONE;
            // 승인 전 평가 중이거나 승인 결과를 아직 모름
            case "PENDING", "HELD" -> PaymentGatewayStatus.IN_PROGRESS;
            // VOIDED는 매입 전 승인 취소라 청구가 없다. 환불 완료(CANCELED)와 구분한다.
            case "DENIED", "DECLINED", "VOIDED" -> PaymentGatewayStatus.ABORTED;
            // 매입 뒤 환불은 돈워리에서 결제 상태를 CAPTURED로 둔다(DonWorry ADR-0058). 환불 완료는 취소 응답으로만 안다
            case "EXPIRED" -> PaymentGatewayStatus.EXPIRED;
            default -> PaymentGatewayStatus.UNKNOWN;
        };
    }
}
