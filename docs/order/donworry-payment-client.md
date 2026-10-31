# DonWorry 결제 API 클라이언트

- 관련: 로드맵 #1, [PG-3] #4, DonWorry #149(결제 API) · #219(HMAC) · #246(CakeShop 키)
- 범위: DonWorry `POST /api/payments` · `GET /api/payments/{paymentId}`를 서명해 호출하는 HTTP 경계만 둔다.
  결제 흐름·`PaymentGateway`·`OWN` 라우팅은 바꾸지 않는다.

## 1. 구성

| 클래스 | 역할 |
|---|---|
| `DonWorryPaymentClient` | HMAC(`DONWORRY-HMAC-V1`) 서명 후 요청·조회. `donworry.payment.enabled=true`일 때만 빈 생성 |
| `DonWorryPaymentRequest` | 요청 본문. `customerRef`는 `member_id` 문자열, FDS 식별자는 선택(null이면 생략) |
| `DonWorryPaymentResponse` | 단건 응답. `PENDING`·`HELD`는 결과가 아니므로 다시 조회한다 |
| `DonWorryPaymentException` | 실패. `resultUnknown()`이면 결제 생성 여부를 모르므로 같은 `Idempotency-Key`·본문으로 다시 보낸다 |

서명 규칙은 픽업 송신(PR #20 `DonWorryPickupClient`)과 같다. 둘 다 병합되면 서명 코드를 한곳으로 모은다.

## 2. 로컬 연결 확인

1. DonWorry: `docker compose up -d` 후 `payment.http.enabled=true`로 백엔드 실행 (DonWorry `docs/demo/local-demo.md` 2절)
2. 같은 값의 `DONWORRY_MERCHANT_HMAC_CAKESHOP`을 **OS 환경변수**로 둔다. `.env`는 Spring 속성으로만 읽혀서
   `System.getenv`로 읽는 이 키에는 쓰이지 않는다.
3. CakeShop에서 실제 요청 테스트:

```bash
DONWORRY_LIVE_BASE_URL=http://localhost:8081 ./gradlew test --tests 'com.cakeshop.domain.payment.infra.DonWorryPaymentClientLiveTests'
```

`DONWORRY_LIVE_BASE_URL`이 없으면 이 테스트는 건너뛴다(CI 포함).

## 3. 미결정 (OWN 게이트웨이 연결 전에 정할 것)

- `PaymentGateway`는 결제창 `paymentKey` → `approve` 구조다. DonWorry는 서버 간 요청 → `PENDING`/`HELD` → 조회 구조라
  인터페이스 변경 여부를 `pg-decisions` ADR로 정해야 한다 (order·payment 담당).
- DonWorry 상태(`PENDING`·`HELD`·`CAPTURED`·`DENIED`·`DECLINED`·`VOIDED`·`EXPIRED`)와 CakeShop 결제 상태 매핑.
- DonWorry에는 가맹점용 취소·환불 API가 아직 없다 (#149 범위 밖). `cancel`은 연결할 대상이 없다.
- 결제창: DonWorry는 카드 입력 화면을 제공하지 않는다. 고객 화면에서 무엇을 받을지(FDS 식별자 포함) 정해야 한다.
- 주문 기한: DonWorry는 `orderCreatedAt` + 10분을 기한으로 본다. CakeShop 주문 만료와 맞춰야 한다.
