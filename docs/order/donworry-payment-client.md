# DonWorry 결제 연동

- 관련: 로드맵 #1, [PG-3] #4, DonWorry #149(결제 API) · #219(HMAC) · #246(CakeShop 키) · #402(호스팅 결제창), DonWorry ADR-0064
- 범위: DonWorry 결제 API 클라이언트, `OWN` 게이트웨이, **돈워리가 띄우는 호스팅 결제창**을 결제 모달 iframe으로 연결.
  주문 완료는 기존 Toss와 같은 성공 콜백 → `confirm` 흐름을 그대로 쓴다.
- 2026-11-02 변경: 예전에는 CakeShop이 결제창(모의 카드 4장)을 직접 그리고 카드 · 기기 · IP를 DonWorry로 보냈다.
  실제 PG처럼 돈워리가 결제창을 직접 띄우는 방식(DonWorry ADR-0064)으로 바꿨다. CakeShop은 카드 정보를 받지 않는다.

## 1. 구성

| 클래스 | 역할 |
|---|---|
| `DonWorryPaymentClient` | HMAC(`DONWORRY-HMAC-V1`) 서명 후 결제 준비 · 조회. `donworry.payment.enabled=true`일 때만 빈 생성 |
| `DonWorryPaymentSessionRequest` · `DonWorryPaymentSessionResponse` | 결제 준비(`POST /api/payment-sessions`) 요청 · 응답. 응답의 `checkoutUrl`을 iframe으로 연다 |
| `DonWorryPaymentResponse` | 서명 조회 응답. `authorized`(카드 승인 완료 여부)로 `PENDING`의 두 의미(평가 중 / 승인 뒤 매입 대기)를 구분한다 |
| `DonWorryPaymentException` | 실패. `resultUnknown()`이면 같은 `Idempotency-Key`·본문으로 한 번 다시 보낸다 |
| `DonWorryPaymentGateway` | `PaymentProvider.OWN` 구현. `paymentKey`는 `DW-<DonWorry paymentId>`. `approve`는 새 승인 없이 조회로 `authorized`를 확인한다 |
| `DonWorryCheckoutService` · `DonWorryCheckoutController` | 결제 준비(`…/donworry/session`), 결제창 성공 복귀(`…/donworry/return`) |

서명 규칙은 픽업 송신(PR #20 `DonWorryPickupClient`)과 같다. 둘 다 병합되면 서명 코드를 한곳으로 모은다.

## 2. 결제 흐름

```text
주문 결제 화면(/orders/{id}/payment): 동의 체크 → [결제하기] → "결제 수단 선택" 모달(토스페이먼츠 · 돈워리)
  ├ 토스 → POST /orders/{id}/payment/gateway/toss (PG를 TOSS로 저장) → 모달 닫고 Toss 결제창 (기존 흐름)
  └ 돈워리 → POST /orders/{id}/payment/gateway/donworry (PG를 OWN으로 저장)
      → POST /orders/{id}/payment/donworry/session
          → DonWorry POST /api/payment-sessions (서명, 주문 · 금액 · successUrl · failUrl)
          ← { sessionKey, checkoutUrl, expiresAt }
      → 같은 모달 안 iframe에 checkoutUrl(돈워리 결제창): 구매자가 돈워리 화면에서 카드를 고르고 결제
      → 돈워리 결제창이 postMessage로 결과 주소를 알림(CakeShop 출처로만 보냄, CakeShop은 돈워리 출처만 받음)
          ├ 승인 완료(PENDING+authorized · HELD · CAPTURED) → successUrl(/orders/{id}/payment/donworry/return?paymentKey&orderId&amount)
          │     → 기존 성공 콜백 → confirm → approve가 서명 조회로 authorized 확인 → 주문 완료
          └ DENIED · DECLINED 등 → failUrl(?code=…) → 재결제 가능
```

- 복귀 주소의 값은 믿지 않는다. `confirm`에서 DonWorry 서명 조회로 금액 · 상태를 다시 확인한다.
- **보류(HELD)도 결제 완료로 본다.** 카드 승인이 끝났기 때문이다(실제 PG의 "승인 = 결제됨"과 같다, DonWorry ADR-0064 4절).
  보류 뒤 보안 직원이 거절(VOIDED)하면 주문에 반영해야 하는데, 이는 돈워리 결제 상태 웹훅을 받는 **후속 PR**에서 한다.
- CakeShop이 보내는 값: 주문번호, 회원 ID, 서버가 확정한 금액, 결제 창 시작 시각, 주문명, 회원 이메일(돈워리 형식 `[^@\s]+@[^@\s]+`에
  맞을 때만), 복귀 주소. 카드 · 기기 · IP는 보내지 않는다. 돈워리 결제창이 구매자에게서 직접 받는다.
- `orderCreatedAt`은 CakeShop 결제 기한 − 10분이다. DonWorry 요청 기한(+10분)이 CakeShop 결제 기한과 같아진다.
- 로컬 결제 상태는 성공 콜백 이후 기존 `PaymentFacade`만 바꾼다.
- PG 선택은 승인 시도 전 READY 결제에만 저장된다. DonWorry 결제가 진행 중이면 Toss로 바꿀 수 없다.
  근거와 한계는 [pg-decisions 006](pg-decisions/006-customer-payment-gateway-choice.md) (채택).

## 3. 로컬 실행

1. DonWorry: `docker compose up -d` 후 `payment.http.enabled=true`로 백엔드 실행 (DonWorry `docs/demo/local-demo.md` 2절), 프론트(`npm run dev`, 결제창 화면)도 띄운다
   - 가맹점 키는 DonWorry DB에 암호화 저장한다. 시드 `CAKESHOP`을 쓰려면 DonWorry에 `DONWORRY_HMAC_MASTER_KEY_V1`(Base64 32바이트)과
     기존 키 `DONWORRY_MERCHANT_HMAC_CAKESHOP`을 두고 `--donworry.integration.legacy-import-enabled=true`로 한 번 기동해 키를 옮긴다 (DonWorry ADR-0052).
2. 같은 값의 `DONWORRY_MERCHANT_HMAC_CAKESHOP`을 CakeShop 쪽에도 **OS 환경변수**로 둔다. `.env`는 Spring 속성으로만 읽혀서
   `System.getenv`로 읽는 이 키에는 쓰이지 않는다.
3. CakeShop `.env`: `DONWORRY_PAYMENT_ENABLED=true`, `DONWORRY_PAYMENT_BASE_URL=http://localhost:8081`,
   `DONWORRY_PAYMENT_SHOP_BASE_URL`(결제창이 돌려보낼 이 쇼핑몰 주소)
4. **CakeShop은 `http://127.0.0.1:8080`으로 연다.** DonWorry 화면(`localhost:5173`)과 같은 `localhost`면 로그인 쿠키가 섞이고,
   복귀 주소(`DONWORRY_PAYMENT_SHOP_BASE_URL`)와 출처가 다르면 결제창의 이동 신호가 전달되지 않는다
   (결제창은 4초 뒤 "결과 화면으로 이동" 링크를 보인다). `DONWORRY_PAYMENT_SHOP_BASE_URL=http://127.0.0.1:8080`으로 맞춘다.
5. 결제 화면에서 결제하기 → 돈워리를 고른다. 이미 승인된 기존 결제는 저장된 PG를 그대로 쓴다.

> 테스트도 `.env`를 읽는다. `.env`에서 `DONWORRY_PAYMENT_ENABLED=true`로 두고 키 환경변수 없이 `./gradlew test`를 돌리면
> 앱 컨텍스트를 쓰는 테스트가 키 설정 오류로 실패한다. 테스트할 때는 키를 OS 환경변수로 두거나 연동 설정을 끈다.
> 브랜치를 바꾼 뒤 지워진 클래스가 `build/`에 남아 있으면 MyBatis 별칭 검색이 깨질 수 있다(`disagree on InnerClasses attribute`). `./gradlew clean test`로 돌린다.

API만 확인할 때:

```bash
DONWORRY_LIVE_BASE_URL=http://localhost:8081 ./gradlew test --tests 'com.cakeshop.domain.payment.infra.DonWorryPaymentClientLiveTests'
```

`DONWORRY_LIVE_BASE_URL`이 없으면 이 테스트는 건너뛴다(CI 포함).

## 4. 알려진 한계 (결정 필요)

- **보류 뒤 거절 반영**: 돈워리 웹훅 수신(후속 PR) 전에는 보류(HELD) 뒤 직원 거절(VOIDED)이 주문에 반영되지 않는다.
  제작 시작 전이면 주문 취소, 제작 시작 뒤면 주문 유지(손실은 돈워리 부담)로 나누는 방향은 DonWorry ADR-0064 4-1에 있고, P6 정산 처리 확인이 남았다.
- **고객 취소**: DonWorry가 HELD 결제의 고객 승인 취소(`POST /api/payments/{id}/cancellations`)를 실행한다(DonWorry #382). CakeShop 주문 취소와의 연결은 후속 PR이다.
  매입된(CAPTURED) 결제의 환불은 DonWorry #383 전까지 503이다.
- 위 구조(성공 콜백 재사용, `approve` = 조회)는 `pg-decisions` ADR로 남길 대상이다.
