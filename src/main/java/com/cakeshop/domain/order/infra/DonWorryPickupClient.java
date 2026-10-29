package com.cakeshop.domain.order.infra;

import com.cakeshop.domain.order.dto.DonWorryPickupMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * ******************************
 * 작성자 : 민정
 * 담당자 : 주환 (order), 민정 (P6 연동)
 * 작성일 : 2026-10-29
 * 기능 : P6 HMAC 픽업 HTTP 송신
 * 설명 : P1의 서명 규칙으로 실제 본문 바이트에 서명하며 운영 키는 환경변수에서만 읽는다.
 * ******************************
 */
@Component
@ConditionalOnProperty(name = "donworry.pickup.enabled", havingValue = "true")
public class DonWorryPickupClient {
    private static final String CONTENT_TYPE = "application/json";
    private final DonWorryPickupProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] key;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    public DonWorryPickupClient(DonWorryPickupProperties properties, ObjectMapper json, Clock clock) {
        this(properties, json, clock, System::getenv);
    }

    DonWorryPickupClient(DonWorryPickupProperties properties, ObjectMapper json, Clock clock,
                        Function<String, String> environment) {
        validateBaseUrl(properties.getBaseUrl());
        if (properties.getMerchantCode() == null || !properties.getMerchantCode().matches("[A-Z0-9]{3,40}")
                || properties.getKeyEnvironmentVariable() == null
                || !properties.getKeyEnvironmentVariable().matches("DONWORRY_MERCHANT_HMAC_[A-Z0-9_]{1,150}")) {
            throw new IllegalStateException("DonWorry 픽업 인증 설정을 확인해 주세요.");
        }
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.key = decodeKey(environment.apply(properties.getKeyEnvironmentVariable()));
    }

    public void send(DonWorryPickupMessage message, boolean completion) {
        var body = json.writeValueAsBytes(completion
                ? new DonWorryPickupMessage.Completion(message.pickedUpAt())
                : new DonWorryPickupMessage.Schedule(message.scheduledPickupAt(), message.items()));
        if (body.length > 65_536) {
            throw new IllegalArgumentException("DonWorry 픽업 요청 크기를 초과했습니다.");
        }
        URI uri = UriComponentsBuilder.fromUri(properties.getBaseUrl())
                .pathSegment("api", "v1", "order-fulfillments", message.orderNumber(),
                        completion ? "completion" : "schedule")
                .build().encode(StandardCharsets.UTF_8).toUri();
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String target = uri.getRawPath();
        String signature = sign(key, properties.getMerchantCode(), timestamp, nonce, "PUT", target,
                CONTENT_TYPE, "", body);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("Content-Type", CONTENT_TYPE)
                .header("X-Merchant-Code", properties.getMerchantCode())
                .header("X-Timestamp", timestamp).header("X-Nonce", nonce).header("X-Signature", signature)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200) {
                throw new IllegalStateException("DonWorry 픽업 응답 상태: " + status);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DonWorry 픽업 전송이 중단됐습니다.");
        } catch (IOException exception) {
            throw new IllegalStateException("DonWorry 픽업 전송에 실패했습니다.");
        }
    }

    static String sign(byte[] key, String merchantCode, String timestamp, String nonce, String method,
                       String target, String contentType, String idempotencyKey, byte[] body) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", "DONWORRY-HMAC-V1", merchantCode, timestamp, nonce,
                    method, target, contentType, idempotencyKey, hash);
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256을 사용할 수 없습니다.");
        }
    }

    private byte[] decodeKey(String encoded) {
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
        throw new IllegalStateException("DonWorry 픽업 HMAC 키 설정을 확인해 주세요.");
    }

    private void validateBaseUrl(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalStateException("DonWorry 픽업 Base URL을 확인해 주세요.");
        }
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("DonWorry 픽업은 HTTPS 또는 로컬 테스트 주소만 허용합니다.");
        }
    }
}
