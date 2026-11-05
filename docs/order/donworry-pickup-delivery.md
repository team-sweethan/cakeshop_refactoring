# CakeShop → DonWorry P6 주문 상품 송신

상태: 로컬 구현·검증. 운영 활성화·배포 연동·담당자 리뷰와 구분한다.
DonWorry에서 상품만 있는 요청을 받을 수 있도록 배포한 뒤 CakeShop 송신을 활성화한다. 기존 경로와 설정 이름은 배포 호환을 위해 유지한다.
결제 PG 호출과 독립된 P6 주문 상품 연동이며 기존 Toss/자체 PG 선택·승인·취소 코드는 변경하지 않는다.

## 전송 흐름

- 일반·장바구니·수제 주문의 상품 스냅샷과 READY 결제 준비가 성공한 트랜잭션에서 내부 이벤트를 발행한다.
- DB 커밋 후에만 전송한다. 롤백 또는 트랜잭션 없는 이벤트 발행으로 HTTP를 실행하지 않는다.
- 상품 스냅샷: PUT /api/v1/order-fulfillments/{orderId}/schedule
  - items[{productName, quantity}]
- orderId는 orders.order_number이며 내부 숫자 PK가 아니다.
- 상품은 현재 상품명이 아니라 order_items.product_name·quantity의 주문 당시 스냅샷을 ID 순서대로 보낸다. 동명 상품을 합치지 않는다.
- 미결제 주문의 상품 목록이 먼저 도착해도 P6는 매입 연결 대기로 보관한다. 이 요청만으로 매입·정산금을 생성하지 않는다.

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
로그에는 내부 orderId만 남긴다. 로그는 영속 전송 이력·자동 복구를 대신하지 않는다.
커밋 직후 프로세스 종료, 일시적 장애, 실패 이벤트의 재기동 후 복구를 보장하는 outbox/재전송 저장소·운영 UI는 아직 없다.
명시적 재확인에는 DonWorryPickupSender.send(orderId)를 같은 저장 데이터로 호출할 수 있다. 외부 공개 재전송 API는 만들지 않았다.
동일 목록을 재전달할 때 P6는 중복 반영하지 않으며 다른 목록은 409로 거절한다.
사용자 재주문·같은 주문 생성 요청의 반복 자체를 송신 재시도로 해석하지 않는다.

## 주요 파일

- OrderService / CustomerCustomOrderService: 원 업무 트랜잭션에서 이벤트 발행
- DonWorryPickupSender: AFTER_COMMIT에서 송신. 실패는 민감정보 없이 기록
- DonWorryPickupQueryService: 별도 읽기 트랜잭션에서 커밋된 주문 스냅샷 생성
- DonWorryPickupClient: HMAC 서명과 HTTP 전송
- DonWorryPickupEvent / DonWorryPickupMessage: 내부 이벤트와 전송용 최소 데이터
- DonWorryPickupProperties: 기본 비활성·서버 주소·환경변수 참조

## 검증

실행 결과는 별도 보고한다. 검증 대상은 P1 고정 벡터, 실제 로컬 HTTP의 본문·서명·새 nonce,
커밋/롤백·외부 실패 분리, 주문 서비스 회귀다.
실제 배포 환경에서 두 서버 간 요청과 저장 결과를 대조한다.
