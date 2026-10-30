# CakeShop → DonWorry P6 픽업 정보 송신

상태: 로컬 구현·검증. 운영 활성화·배포 연동·담당자 리뷰와 구분한다.
CakeShop 기준 dev f63692d0, DonWorry 수신 계약 PR #334 da62b2d.
결제 PG 호출과 독립된 P6 픽업 연동이며 기존 Toss/자체 PG 선택·승인·취소 코드는 변경하지 않는다.

## 전송 흐름

- 일반·장바구니·수제 주문의 상품 스냅샷과 READY 결제 준비가 성공한 트랜잭션에서 내부 이벤트를 발행한다.
- DB 커밋 후에만 전송한다. 롤백 또는 트랜잭션 없는 이벤트 발행으로 HTTP를 실행하지 않는다.
- 예정: PUT /api/v1/order-fulfillments/{orderId}/schedule
  - scheduledPickupAt, items[{productName, quantity}]
- 실제 완료: PUT /api/v1/order-fulfillments/{orderId}/completion
  - pickedUpAt만 전송. 상품·예정 시각을 다시 보내지 않는다.
- orderId는 orders.order_number이며 내부 숫자 PK가 아니다.
- orders.pickup_at / picked_up_at은 서울 LocalDateTime이다. Asia/Seoul을 적용해 UTC Instant로 한 번 변환하고 마이크로초 정밀도를 유지한다.
- 상품은 현재 상품명이 아니라 order_items.product_name·quantity의 주문 당시 스냅샷을 ID 순서대로 보낸다. 동명 상품을 합치지 않는다.
- 미결제 주문의 픽업이 먼저 도착해도 P6는 매입 연결 대기로 보관한다. 이 요청만으로 매입·정산금을 생성하지 않는다.

## HMAC와 설정

P1의 DonWorry docs/identity/219-merchant-hmac.md 계약을 따른다. 키 원문·서명·본문·nonce를 로그에 기록하지 않는다.
기본 donworry.pickup.enabled=false라서 설정·키가 준비되지 않은 환경에서는 외부 호출을 하지 않는다.

| 환경변수 | 의미 |
| --- | --- |
| DONWORRY_PICKUP_ENABLED | true일 때만 송신 Bean 등록 |
| DONWORRY_PICKUP_BASE_URL | DonWorry API 서버의 origin. 경로·쿼리·사용자 정보 금지 |
| DONWORRY_MERCHANT_CODE | P1 등록 코드, 기본 CAKESHOP |
| DONWORRY_MERCHANT_HMAC_CAKESHOP | P1과 공유한 32바이트 이상 무작위 키의 표준 Base64 |

Base URL은 HTTPS를 사용한다. localhost/127.0.0.1/[::1]만 HTTP 로컬 테스트를 허용한다.
배포 도메인은 인프라 담당이 외부 API 접근 가능 여부를 확인한 주소를 입력한다. 문서의 도메인만 보고 자동 활성화하지 않는다.
DonWorry에도 같은 가맹점 코드·키 등록과 settlement.pickup-http.enabled=true가 필요하다.
요청 본문에 merchantId를 넣지 않는다. DonWorry는 HMAC으로 인증된 가맹점 ID를 사용한다.

서명은 DONWORRY-HMAC-V1과 코드·현재 Unix 초·새 UUID v4 nonce·PUT·원본 경로·application/json·빈 멱등 키·본문 SHA-256의 9줄이다.
실제로 전송하는 JSON 바이트를 한 번 생성해 그대로 해시·서명·전송한다. HTTP마다 새 nonce를 만든다.
요청 본문은 최대 65,536바이트, 연결 제한 2초·요청 제한 3초이며 리다이렉트를 따라가지 않는다.

## 실패 범위와 남은 작업

이 단계는 커밋 후 1회 송신이다. 200 외 응답·연결 실패는 실패로 취급하지만 이미 커밋된 주문을 실패 응답으로 뒤집지 않는다.
로그에는 내부 orderId·완료 여부만 남긴다. 로그는 영속 전송 이력·자동 복구를 대신하지 않는다.
커밋 직후 프로세스 종료, 일시적 장애, 실패 이벤트의 재기동 후 복구를 보장하는 outbox/재전송 저장소·운영 UI는 아직 없다.
명시적 재확인에는 DonWorryPickupSender.send(orderId, completion)를 같은 저장 데이터로 호출할 수 있다. 외부 공개 재전송 API는 만들지 않았다.
동일 시각·목록을 재전달할 때 P6는 중복 반영하지 않으며 다른 시각·목록은 409로 거절한다.
사용자 재주문·같은 주문 생성 요청의 반복 자체를 송신 재시도로 해석하지 않는다.

## 주요 파일

- OrderService / CustomerCustomOrderService / FulfillmentService: 원 업무 트랜잭션에서 이벤트 발행
- DonWorryPickupSender: AFTER_COMMIT에서 송신. 실패는 민감정보 없이 기록
- DonWorryPickupQueryService: 별도 읽기 트랜잭션에서 커밋된 주문 스냅샷 생성
- DonWorryPickupClient: HMAC 서명과 HTTP 전송
- DonWorryPickupEvent / DonWorryPickupMessage: 내부 이벤트와 전송용 최소 데이터
- DonWorryPickupProperties: 기본 비활성·서버 주소·환경변수 참조

## 검증

실행 결과는 별도 보고한다. 검증 대상은 P1 고정 벡터, 실제 로컬 HTTP의 본문·서명·새 nonce,
서울→UTC 변환, 커밋/롤백·외부 실패 분리, 주문 서비스 회귀다.
실제 CakeShop 송신 클래스를 DonWorry PR #334 수신부에 연결한 별도 로컬 검증에서는
테스트 주문 데이터 → 실제 HMAC 필터 → P6 서비스 → 격리 MariaDB 저장과 매입 연결을 확인한다.
개인 donworry DB나 운영 서버·실제 키는 검증에 사용하지 않는다.

### 2026-10-29 로컬 실행 결과

- Java 21, Gradle: unitTest 전체 1,409개 성공, 실패·건너뜀 0.
- mariaDbTest --tests '*OrderServiceIntegrationTests' --tests '*OrderServiceRollbackIntegrationTests': 3개 성공.
- DonWorry PR #334 수신부 + 실제 CakeShop 송신 클래스의 별도 로컬 통합 검사: 1개 성공.
  테스트 저장 주문 데이터로 생성한 실제 HTTP 요청 4회의 HMAC·새 nonce 인증, 예정 상품 2건 저장,
  중복 부모/항목 방지, 매입 선후 도착 연결, 예정/실제 픽업+72시간을 확인했다.
- P1의 키 조회만 테스트 키로 대체했고 실제 HMAC 필터·nonce DB·CurrentMerchant·P6 서비스·MariaDB를 거쳤다.
- 로컬 교차 저장소 검증 코드는 DonWorry 저장소 밖 .p6-local-backups/cakeshop-pickup-e2e에 보관했다.
  CakeShop의 운영·단위 테스트 소스에 상대 저장소 클래스를 import하지 않는다.
- CakeShop 전체 MariaDB suite, 실제 브라우저 주문 생성부터 양 서버까지의 실행, 배포 HTTPS·운영 키·프록시는 미검증이다.
- 기존 주문 상태 전이·결제 PG 처리·DB migration·build.gradle은 변경하지 않았다.

### PR 분리 후 검증

dev f63692d0 기반 픽업 송신 단독 브랜치에서 ./gradlew unitTest mariaDbTest를 실행했다.
단위 1,409개와 MariaDB 727개가 모두 성공했고 실패·오류·스킵은 없다.
시간대 수정 PR 없이도 독립적으로 통과한다. 운영 호출·자동 재전달의 미검증 범위는 위와 같다.
