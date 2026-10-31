# DonWorry 결제 연동

- 관련: 로드맵 #1, [PG-3] #4, DonWorry #149(결제 API) · #219(HMAC) · #246(CakeShop 키), DonWorry 프로토타입 v4.2 결제창
- 범위: DonWorry 결제 API 클라이언트, `OWN` 게이트웨이, CakeShop 안의 DonWorry 결제창(시연용 모의 카드 4장).
  주문 완료는 기존 Toss와 같은 성공 콜백 → `confirm` 흐름을 그대로 쓴다.

## 1. 구성

| 클래스 | 역할 |
|---|---|
| `DonWorryPaymentClient` | HMAC(`DONWORRY-HMAC-V1`) 서명 후 요청·조회. `donworry.payment.enabled=true`일 때만 빈 생성 |
| `DonWorryPaymentRequest` | 요청 본문. `customerRef`는 `member_id` 문자열, FDS 식별자는 선택(null이면 생략) |
| `DonWorryPaymentResponse` | 단건 응답. `PENDING`·`HELD`는 결과가 아니므로 다시 조회한다 |
| `DonWorryPaymentException` | 실패. `resultUnknown()`이면 결제 생성 여부를 모르므로 같은 `Idempotency-Key`·본문으로 다시 보낸다 |
| `DonWorryPaymentGateway` | `PaymentProvider.OWN` 구현. `paymentKey`는 `DW-<DonWorry paymentId>` |
| `DonWorryCheckoutService` · `DonWorryCheckoutController` | 결제창 열기, 결제 요청, 결과 대기 조회 |
| `DonWorryDemoCard` | 프로토타입 `SHOP_CARDS`(A·B·D·C)와 같은 시연용 카드 |

서명 규칙은 픽업 송신(PR #20 `DonWorryPickupClient`)과 같다. 둘 다 병합되면 서명 코드를 한곳으로 모은다.

## 2. 결제 흐름

```text
주문 결제 화면(/orders/{id}/payment): 동의 체크 → [결제하기] → "결제 수단 선택" 모달(토스페이먼츠 · 돈워리)
  ├ 토스 → POST /orders/{id}/payment/gateway/toss (PG를 TOSS로 저장) → 모달 닫고 Toss 결제창 (기존 흐름)
  └ 돈워리 → POST /orders/{id}/payment/gateway/donworry (PG를 OWN으로 저장)
      → 같은 모달 안에 DonWorry 결제창 조각(/orders/{id}/payment/donworry/panel): 카드 A·B·D·C 선택
      └ POST /orders/{id}/payment/donworry/requests → DonWorry POST /api/payments (서명, 창마다 새 Idempotency-Key)
          ├ CAPTURED        → 기존 성공 URL(paymentKey=DW-…) → confirm → approve가 DonWorry 조회로 CAPTURED 확인 → 주문 완료
          ├ PENDING · HELD  → 모달 안에서 "확인 중" 표시, 3초마다 …/DW-…/progress 조회 → 결과가 나오면 성공·실패 URL로 이동
          └ DENIED · DECLINED · VOIDED · EXPIRED → 기존 실패 URL(code=FDS_REJECTED 등) → 재결제 가능
```

- 실제 PG의 PC 레이어 결제창처럼 모달 안에 돈워리 화면만 보인다. 돈워리가 결제창을 직접 호스팅하는 방식(돈워리 도메인)은
  DonWorry 쪽 화면·계약이 필요해 팀 논의 후 정한다.
- 스크립트 없이 쓰는 대체 경로로 전체 화면 결제창(`/orders/{id}/payment/donworry`)과 결과 대기 화면을 남겨 둔다.

- DonWorry로 보내는 값: 주문번호, 회원 ID, 서버가 확정한 금액, 결제 창 시작 시각, 회원별 카드 토큰·마스킹 표시값,
  기기 쿠키(`cakeshop_device`), 접속 IP, 회원 이메일. 카드번호 원문은 없다.
- `orderCreatedAt`은 CakeShop 결제 기한 − 10분이다. DonWorry 요청 기한(+10분)이 CakeShop 결제 기한과 같아진다.
- 로컬 결제 상태는 성공 콜백 이후 기존 `PaymentFacade`만 바꾼다.
- PG 선택은 승인 시도 전 READY 결제에만 저장된다. DonWorry 결제가 진행 중(PENDING·HELD·CAPTURED)이면 Toss로 바꿀 수 없다.
  근거와 한계는 [pg-decisions 006](pg-decisions/006-customer-payment-gateway-choice.md) (제안).
- Toss 키가 없어도 DonWorry 연동이 켜져 있으면 결제 버튼이 열리고 Toss 선택지만 비활성으로 보인다.

## 3. 로컬 실행

1. DonWorry: `docker compose up -d` 후 `payment.http.enabled=true`로 백엔드 실행 (DonWorry `docs/demo/local-demo.md` 2절)
   - DonWorry #354 PR B(2026-10-31) 이후 가맹점 키는 DonWorry DB에 암호화 저장한다. 시드 `CAKESHOP`을 쓰려면
     DonWorry에 `DONWORRY_HMAC_MASTER_KEY_V1`(Base64 32바이트)과 기존 키 `DONWORRY_MERCHANT_HMAC_CAKESHOP`을 두고
     `--donworry.integration.legacy-import-enabled=true`로 한 번 기동해 키를 옮긴다 (DonWorry ADR-0052).
     사장님 화면 키 발급·연결 확인(PR C)이 마무리되면 이 절차는 바뀐다.
2. 같은 값의 `DONWORRY_MERCHANT_HMAC_CAKESHOP`을 CakeShop 쪽에도 **OS 환경변수**로 둔다. `.env`는 Spring 속성으로만 읽혀서
   `System.getenv`로 읽는 이 키에는 쓰이지 않는다.
3. CakeShop `.env`: `DONWORRY_PAYMENT_ENABLED=true`, `DONWORRY_PAYMENT_BASE_URL=http://localhost:8081`
   (`PAYMENT_GATEWAY`는 초기값일 뿐이며 고객이 결제창 선택에서 바꾼다)
4. 결제 화면에서 결제하기 → 돈워리를 고른다. 이미 승인된 기존 결제는 저장된 PG를 그대로 쓴다.

> 테스트도 `.env`를 읽는다. `.env`에서 `DONWORRY_PAYMENT_ENABLED=true`로 두고 키 환경변수 없이 `./gradlew test`를 돌리면
> 앱 컨텍스트를 쓰는 테스트가 키 설정 오류로 실패한다. 테스트할 때는 키를 OS 환경변수로 두거나 연동 설정을 끈다.

API만 확인할 때:

```bash
DONWORRY_LIVE_BASE_URL=http://localhost:8081 ./gradlew test --tests 'com.cakeshop.domain.payment.infra.DonWorryPaymentClientLiveTests'
```

`DONWORRY_LIVE_BASE_URL`이 없으면 이 테스트는 건너뛴다(CI 포함).

## 4. 알려진 한계 (결정 필요)

- **HELD 중 주문 만료**: CakeShop은 결제 기한(10분)이 지나면 결제 대기 주문을 만료한다. DonWorry HELD가 그 뒤에
  CAPTURED가 되면 CakeShop 주문은 이미 만료돼 완료되지 않는다. HELD 동안 만료를 멈출지 order·payment 담당이 정해야 한다.
- **취소·환불**: DonWorry에 가맹점용 취소·환불 API가 없다(DonWorry #153). `OWN` 결제의 `cancel`은 실패로 남고 복구 대상이 된다.
- **카드 C(한도 초과)·D(해외 발급)**: DonWorry 모의 승인은 항상 성공하고 카드 발급국을 받지 않는다. 현재는 A·B와 같은 규칙으로 평가된다.
  프로토타입처럼 동작하려면 DonWorry(P5·P2) 쪽 변경이 필요하다.
- **접속 IP**: 프록시 뒤에서는 `request.getRemoteAddr()`가 프록시 주소다. 배포 환경의 전달 헤더 신뢰 범위를 정해야 한다.
- 위 구조(성공 콜백 재사용, `approve` = 조회)는 `pg-decisions` ADR로 남길 대상이다.
