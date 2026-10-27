# 주문 도메인 연동 개요

## 문서 성격

이 문서는 장바구니·주문·결제 도메인이 연결되는 현재 구현의 설계 기록이다. 구현 규약이나 도메인 간
계약의 정본이 아니므로, 작업 시에는 `AGENTS.md`, `docs/conventions.md`와 각 도메인의 공개 Service를
우선 확인한다.

## 목적

장바구니에서 선택한 일반 상품 여러 건을 하나의 주문서로 전달하고, 결제가 실제로 완료된 경우에만
그 주문에 포함된 장바구니 항목을 삭제한다. 결제 대기, 결제 실패, 결제 만료, 주문 취소에서는
장바구니 항목을 유지한다.

## 책임 경계

| 도메인 | 책임 | 공개 계약 |
|---|---|---|
| `cart` | 로그인 회원의 장바구니 항목 조회와 삭제 | `CartOrderQueryService`, `CartPaymentCommandService` |
| `order` | 선택 항목으로 주문 생성 및 주문-장바구니 연결 이력 보관 | `OrderCartQueryService` |
| `payment` | 결제 완료 확정 후 이벤트 발행 | `GeneralPaymentCompletedEvent` |

- 다른 도메인은 소유 도메인의 Entity, Mapper, 테이블을 직접 사용하지 않는다.
- 주문 생성 시 상품·옵션·수량·금액은 화면 값이 아니라 `CartOrderQueryService`와 상품 공개 조회 계약으로
  다시 확인한다.
- 장바구니 삭제는 `cart` 도메인만 수행한다.

## 장바구니 연동

### 주문서 및 주문 생성

```text
장바구니에서 항목 선택
  → GET /orders/checkout/cart?itemIds=...
  → cart: 선택 항목의 회원 소유권·주문 가능 여부 확인
  → 주문서에 서버 기준 상품·옵션·수량·원금액 표시
  → POST /orders/general/cart
  → order: 선택 항목과 현재 상품 정보를 다시 검증
  → orders / order_items / order_item_options / payments 저장
  → order_cart_items에 (order_id, cart_item_id) 연결 저장
```

선택 항목이 없거나 다른 회원의 항목을 요청하면 주문서로 진행하지 않는다.

### 결제 전 장바구니 변경 처리

`order_cart_items`에는 주문 생성 당시의 `cart_item_id`와 `snapshot_quantity`를 함께 저장한다.
결제 완료 후 장바구니 정리에서는 현재 장바구니 수량이 `snapshot_quantity`와 같은 항목만 삭제한다.
따라서 결제 대기 중 수량을 변경한 항목은 새 사용자의 의도로 보고 보존한다.

### 장바구니 선택 한도

`CartOrderForm.cartItemIds`에는 장바구니 항목 종류의 상한을 두지 않는다.
따라서 A·B·C처럼 서로 다른 장바구니 항목은 모두 선택해 하나의 주문으로 만들 수 있다.
이는 상품별 구매 수량 제한과 별개이며, 상품별 수량은 상품 판매 정책과 재고 검증으로 제한한다.

## 결제 연동

### 결제 완료 후 장바구니 정리

```text
PG 결제 승인 또는 0원 결제 완료
  → payment: 결제·주문·쿠폰 사용 상태를 하나의 트랜잭션으로 확정
  → GeneralPaymentCompletedEvent 발행
  → 결제 트랜잭션 커밋
  → cart: AFTER_COMMIT 이벤트 수신
  → order: OrderCartQueryService로 주문의 회원 ID·선택 cart_item_id 조회
  → cart: 해당 회원의 해당 장바구니 항목만 삭제
```

이벤트 리스너는 `AFTER_COMMIT` 단계에서 동작한다. 따라서 결제 트랜잭션이 롤백되면 장바구니 삭제는
실행되지 않는다.

### `order_cart_items`의 역할

`order_cart_items`는 주문 상품의 가격·옵션 스냅샷을 저장하는 테이블이 아니다. 실제 주문 스냅샷은
`order_items`, `order_item_options`가 소유한다. 이 테이블은 주문 시점에 선택했던 `cart_item_id`만
보관하여 결제 완료 후 삭제 대상을 정확히 식별하는 연결 이력이다.

| 상황 | 동작 |
|---|---|
| 결제 성공 | 해당 주문에 연결된 장바구니 항목만 삭제 |
| 0원 결제 성공 | 해당 주문에 연결된 장바구니 항목만 삭제 |
| 결제 실패·만료 | 이벤트가 발행되지 않아 장바구니 유지 |
| 결제 전 회원이 항목을 직접 삭제 | 주문·결제는 유지, 후속 정리는 0건 삭제도 성공으로 처리 |
| 이벤트 재수신 | 이미 삭제된 항목은 0건 삭제로 처리해 결과가 변하지 않음 |

`cart_item_id`에는 FK를 두지 않는다. 결제 전 사용자의 장바구니 직접 삭제가 주문 또는 결제 처리를
막지 않도록 하기 위함이다. 반면 `order_id`는 주문 데이터이므로 `orders.id`를 참조한다.

### 현재 제한과 후속 검토

- 커밋 후 장바구니 정리가 실패해도 이미 성공한 결제 결과를 롤백하지 않는다.
- 현재는 경고 로그만 남긴다. 재시도, 운영 알림, 실패 이력 보관이 필요해지면 이벤트 재처리 정책을
  별도 합의한다.
- 이 문서는 장바구니 선택 주문과 결제 완료 후 정리만 다룬다. 쿠폰 할인 검증·예약·사용 확정 정책은
  `docs/coupon/coupon_conventions.md`를 정본으로 한다.

## 자체 PG 전환 준비

신규 선택과 기존 결제 라우팅은 [기능 명세](pg-integration-spec.md), 결정 근거는
[ADR 003](pg-decisions/003-payment-provider-routing.md), API 계약 전후의 작업 구분과 배포·장애·설정 복귀
절차는 [PG 전환 배포와 장애 대응](pg-rollout.md)을 따른다.
