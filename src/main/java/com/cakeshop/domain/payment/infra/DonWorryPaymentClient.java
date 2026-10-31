package com.cakeshop.domain.payment.infra;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ******************************
 * 작성자 : 수민
 * 담당자 : 주환 (payment)
 * 작성일 : 2026-10-31
 * 기능 : DonWorry 결제 요청·조회 HTTP 클라이언트
 * 설명 : DonWorry #149 결제 API를 #219 HMAC(DONWORRY-HMAC-V1)으로 서명해 호출한다.
 *        PaymentGateway 연결(OWN)은 결제창·상태 매핑 결정 후 별도 작업이며, 이 클래스는 HTTP 경계만 맡는다.
 *        서명 규칙은 픽업 송신(DonWorryPickupClient)과 같다. 두 클라이언트가 병합되면 서명 코드를 한곳으로 모은다.
 * ******************************
 */
@Component
@ConditionalOnProperty(name = "donworry.payment.enabled", havingValue = "true")
public class DonWorryPaymentClient {

    private static final String PAYMENTS_PATH = "/api/payments";
    private static final String ORDERS_PATH = "/api/payment-orders";
    private static final String CONTENT_TYPE = MediaType.APPLICATION_JSON_VALUE;

    private final RestClient restClient;
    private final ObjectMapper json;
    private final Clock clock;
    private final String merchantCode;
    private final byte[] key;

    @Autowired
    public DonWorryPaymentClient(DonWorryPaymentProperties properties, ObjectMapper json, Clock clock) {
        this(properties, restClient(properties), json, clock, System::getenv);
    }

    DonWorryPaymentClient(DonWorryPaymentProperties properties, RestClient restClient, ObjectMapper json,
                          Clock clock, Function<String, String> environment) {
        if (properties.getMerchantCode() == null || !properties.getMerchantCode().matches("[A-Z0-9]{3,40}")
                || properties.getKeyEnvironmentVariable() == null
                || !properties.getKeyEnvironmentVariable().matches("DONWORRY_MERCHANT_HMAC_[A-Z0-9_]{1,150}")) {
            throw new IllegalStateException("DonWorry 결제 인증 설정을 확인해 주세요.");
        }
        this.restClient = restClient;
        this.json = json;
        this.clock = clock;
        this.merchantCode = properties.getMerchantCode();
        this.key = decodeKey(environment.apply(properties.getKeyEnvironmentVariable()));
    }

    /**
     * 결제를 요청한다. 응답을 못 받아 다시 보낼 때는 같은 idempotencyKey와 같은 본문을 써야 같은 시도로 처리된다.
     * PENDING·HELD는 202이며 결제 성공이 아니다.
     */
    public DonWorryPaymentResponse request(DonWorryPaymentRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key가 필요합니다.");
        }
        byte[] body = json.writeValueAsBytes(request);
        return exchange(HttpMethod.POST, PAYMENTS_PATH, body, idempotencyKey, DonWorryPaymentResponse.class);
    }

    /** 결제 단건 상태를 조회한다. 조회는 평가·승인을 다시 실행하지 않는다. */
    public DonWorryPaymentResponse find(long paymentId) {
        return exchange(HttpMethod.GET, PAYMENTS_PATH + "/" + paymentId, new byte[0], "",
                DonWorryPaymentResponse.class);
    }

    /** 주문의 최신 결제 시도를 조회한다. DonWorry에 시도가 없으면 비어 있다. */
    public Optional<DonWorryPaymentResponse> findLatestAttempt(String orderRef) {
        // 주문번호는 영문·숫자·하이픈이라 그대로 query에 둔다. 서명 대상도 같은 원문이다.
        if (orderRef == null || !orderRef.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("주문번호 형식을 확인해 주세요.");
        }
        try {
            DonWorryPaymentOrderResponse order = exchange(HttpMethod.GET,
                    ORDERS_PATH + "?orderRef=" + orderRef + "&size=1", new byte[0], "",
                    DonWorryPaymentOrderResponse.class);
            return Optional.ofNullable(order.latestPayment());
        } catch (DonWorryPaymentException exception) {
            if (exception.getStatus() == 404) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    private <T> T exchange(HttpMethod method, String target, byte[] body, String idempotencyKey, Class<T> type) {
        String contentType = body.length == 0 ? "" : CONTENT_TYPE;
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = sign(key, merchantCode, timestamp, nonce, method.name(), target,
                contentType, idempotencyKey, body);
        try {
            RestClient.RequestBodySpec spec = restClient.method(method).uri(target)
                    .header("X-Merchant-Code", merchantCode)
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature)
                    .accept(MediaType.APPLICATION_JSON);
            if (!idempotencyKey.isEmpty()) {
                spec.header("Idempotency-Key", idempotencyKey);
            }
            if (body.length > 0) {
                spec.header(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE).body(body);
            }
            T response = spec.retrieve().body(type);
            if (response == null) {
                throw new DonWorryPaymentException(0, null);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new DonWorryPaymentException(exception.getStatusCode().value(),
                    problemCode(exception.getResponseBodyAsByteArray()));
        } catch (RestClientException exception) {
            // 연결 실패·시간 초과는 결제 생성 여부를 알 수 없다.
            throw new DonWorryPaymentException(0, null);
        }
    }

    static String sign(byte[] key, String merchantCode, String timestamp, String nonce, String method,
                       String target, String contentType, String idempotencyKey, byte[] body) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", "DONWORRY-HMAC-V1", merchantCode, timestamp, nonce,
                    method, target, contentType, idempotencyKey, hash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256을 사용할 수 없습니다.");
        }
    }

    private String problemCode(byte[] body) {
        try {
            JsonNode code = json.readTree(body).get("code");
            return code == null || !code.isString() ? null : code.asString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded != null && encoded.length() <= 512) {
            try {
                byte[] decoded = Base64.getDecoder().decode(encoded);
                if (decoded.length >= 32) {
                    return decoded;
                }
            } catch (IllegalArgumentException ignored) {
                // 입력값을 오류 메시지에 포함하지 않는다.
            }
        }
        throw new IllegalStateException("DonWorry 결제 HMAC 키 설정을 확인해 주세요.");
    }

    private static RestClient restClient(DonWorryPaymentProperties properties) {
        URI baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !(baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/"))) {
            throw new IllegalStateException("DonWorry 결제 Base URL을 확인해 주세요.");
        }
        boolean local = "localhost".equals(baseUrl.getHost()) || "127.0.0.1".equals(baseUrl.getHost())
                || "[::1]".equals(baseUrl.getHost());
        if (!"https".equals(baseUrl.getScheme()) && !(local && "http".equals(baseUrl.getScheme()))) {
            throw new IllegalStateException("DonWorry 결제는 HTTPS 또는 로컬 테스트 주소만 허용합니다.");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder()
                .baseUrl(baseUrl.toString().replaceAll("/$", ""))
                .requestFactory(requestFactory)
                .build();
    }
}
