package com.cakeshop.domain.payment.infra;

import com.cakeshop.domain.payment.entity.PaymentProvider;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.global.error.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry를 자체 PG(OWN)로 연결
 * 설명 : DonWorry 결제창은 CakeShop 화면에서 결제를 요청하고, 매입 완료(CAPTURED)가 확인된 결제만 기존 성공 콜백으로 넘긴다.
 *        따라서 approve는 새 승인을 요청하지 않고 DonWorry 상태를 조회해 CAPTURED인지 확인한다.
 *        paymentKey는 "DW-" + DonWorry paymentId다.
 *        DonWorry에는 가맹점용 취소·환불 API가 아직 없어 cancel은 실패로 남기고 복구 대상으로 둔다.
 * ******************************
 */
@Component
@ConditionalOnProperty(name = "donworry.payment.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DonWorryPaymentGateway implements PaymentGateway {

    public static final String PAYMENT_KEY_PREFIX = "DW-";
    public static final String USER_CANCELED = "DONWORRY_USER_CANCELED";
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
                toGatewayStatus(response.paymentStatus()),
                response.amount(),
                response.statusChangedAt() == null
                        ? null
                        : LocalDateTime.ofInstant(response.statusChangedAt(), clock.getZone()),
                null
        ));
    }

    @Override
    public CancellationResult cancel(String paymentKey, String reason, String idempotencyKey) {
        throw new BusinessException(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
    }

    static PaymentGatewayStatus toGatewayStatus(String donWorryStatus) {
        if (donWorryStatus == null) {
            return PaymentGatewayStatus.UNKNOWN;
        }
        return switch (donWorryStatus) {
            case "CAPTURED" -> PaymentGatewayStatus.DONE;
            case "PENDING", "HELD" -> PaymentGatewayStatus.IN_PROGRESS;
            // VOIDED는 매입 전 승인 취소라 청구가 없다. 환불 완료(CANCELED)와 구분한다.
            case "DENIED", "DECLINED", "VOIDED" -> PaymentGatewayStatus.ABORTED;
            case "EXPIRED" -> PaymentGatewayStatus.EXPIRED;
            default -> PaymentGatewayStatus.UNKNOWN;
        };
    }
}
