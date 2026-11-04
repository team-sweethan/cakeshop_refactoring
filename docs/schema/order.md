# 주문 스키마

- 담당: 주환
- 테이블: `orders`, `order_items`, `order_item_options`, `order_item_images`, `order_cart_items`
- 정본: Flyway migration 적용 결과

## `orders`

주문자·픽업 정보, 금액과 주문 상태 전이 시각을 저장한다.

| 컬럼 | 타입 | 키 | Null | 기본값 | 의미 |
|---|---|---|---|---|---|
| `id` | BIGINT | PK | X | AUTO_INCREMENT | 주문 식별자 |
| `order_number` | VARCHAR(50) | UK | X | 없음 | 외부 노출 주문번호 |
| `member_id` | BIGINT | FK, UK | X | 없음 | 주문 회원 식별자 |
| `request_key` | VARCHAR(36) | UK | O | NULL | 회원별 주문 요청 중복 방지 키 |
| `orderer_name` | VARCHAR(50) |  | X | 없음 | 주문자 이름 |
| `orderer_phone` | VARCHAR(30) |  | X | 없음 | 주문자 전화번호 |
| `pickup_name` | VARCHAR(50) |  | X | 없음 | 픽업자 이름 |
| `pickup_phone` | VARCHAR(30) |  | X | 없음 | 픽업자 전화번호 |
| `original_amount` | DECIMAL(12, 0) |  | X | 없음 | 할인 전 금액 |
| `discount_amount` | DECIMAL(12, 0) |  | X | `0` | 할인 금액 |
| `final_amount` | DECIMAL(12, 0) |  | X | 없음 | 최종 결제 금액 |
| `status` | VARCHAR(30) | INDEX | X | `'PENDING_PAYMENT'` | 주문 상태 |
| `pickup_at` | DATETIME(6) | INDEX | X | 없음 | 픽업 예정 시각 |
| `payment_expires_at` | DATETIME(6) | INDEX | O | NULL | 결제 만료 시각 |
| `request_message` | TEXT |  | O | NULL | 주문 요청사항 |
| `reject_reason` | TEXT |  | O | NULL | 주문 거절 사유 |
| `approved_at` | DATETIME(6) | INDEX | O | NULL | 승인 시각 |
| `rejected_at` | DATETIME(6) |  | O | NULL | 거절 시각 |
| `ready_at` | DATETIME(6) |  | O | NULL | 픽업 준비 완료 시각 |
| `picked_up_at` | DATETIME(6) |  | O | NULL | 픽업 시각 |
| `canceled_at` | DATETIME(6) |  | O | NULL | 취소 시각 |
| `cancel_reason` | TEXT |  | O | NULL | 취소 사유 |
| `canceled_by` | VARCHAR(30) |  | O | NULL | 취소 주체 |
| `pickup_reminder_sent_at` | DATETIME(6) |  | O | NULL | 픽업 알림 발송 시각 |
| `created_at` | DATETIME(6) | INDEX | X | `CURRENT_TIMESTAMP(6)` | 생성 시각 |
| `updated_at` | DATETIME(6) | INDEX | X | `CURRENT_TIMESTAMP(6)` | 수정 시각, 수정 시 자동 갱신 |
| `order_type` | VARCHAR(30) | INDEX | X | 없음 | 주문 유형 |
| `under_review_at` | DATETIME(6) |  | O | NULL | 주문 확인 중(`UNDER_REVIEW`) 시작 시각. 주문 제작 검토, 또는 PG가 결제를 확인 중인 일반 주문 |
| `expired_at` | DATETIME(6) |  | O | NULL | 주문 만료 시각 |
| `approved_by` | BIGINT | FK | O | NULL | 승인 처리 회원 식별자 |
| `rejected_by` | BIGINT | FK | O | NULL | 거절 처리 회원 식별자 |
| `picked_up_by` | BIGINT | FK | O | NULL | 픽업 처리 회원 식별자 |
| `review_proceeded_by` | BIGINT | FK | O | NULL | PG(돈워리) 결제 확인 중에 확인 창에 동의하고 처음 진행한 관리자 |
| `review_proceeded_at` | DATETIME(6) |  | O | NULL | 위 진행 시각. 진행 뒤 PG가 거절하면 손실은 가맹점 부담(DonWorry ADR-0064 4-1) |

- UK: `uk_orders_order_number` (`order_number`)
- UK: `uk_orders_member_request_key` (`member_id`, `request_key`)
- FK: `member_id`, `approved_by`, `rejected_by`, `picked_up_by`, `review_proceeded_by` → 각각 `members.id`
- CHECK: `order_type IN ('GENERAL', 'CUSTOM')`
- CHECK: `status IN ('PENDING_PAYMENT', 'UNDER_REVIEW', 'IN_PRODUCTION', 'READY_FOR_PICKUP', 'PICKED_UP', 'CANCELED', 'REJECTED', 'EXPIRED')`
- CHECK: 세 금액은 0 이상이고 `REJECTED` 상태에는 `reject_reason`이 필요하다.
- INDEX: `idx_orders_status` (`status`)
- INDEX: `idx_orders_pickup_at` (`pickup_at`)
- INDEX: `idx_orders_payment_expires_at` (`payment_expires_at`)
- INDEX: `idx_orders_member_pending_payment_expires_at` (`member_id`, `status`, `payment_expires_at`, `id`)
- INDEX: `idx_orders_created_at_status` (`created_at`, `status`)
- INDEX: `idx_orders_updated_at_created_at` (`updated_at`, `created_at`)
- INDEX: `idx_orders_custom_production_due` (`order_type`, `status`, `approved_at`)

## `order_items`

주문 시점의 상품·가격·준비 정책과 재고 처리 이력을 저장한다.

| 컬럼 | 타입 | 키 | Null | 기본값 | 의미 |
|---|---|---|---|---|---|
| `id` | BIGINT | PK | X | AUTO_INCREMENT | 주문 항목 식별자 |
| `order_id` | BIGINT | FK | X | 없음 | 주문 식별자 |
| `product_id` | BIGINT | FK | X | 없음 | 상품 식별자 |
| `product_name` | VARCHAR(150) |  | X | 없음 | 상품명 스냅샷 |
| `product_type` | VARCHAR(30) |  | X | 없음 | 상품 유형 스냅샷 |
| `quantity` | INT UNSIGNED |  | X | 없음 | 주문 수량 |
| `base_price` | DECIMAL(12, 0) |  | X | 없음 | 기본 가격 스냅샷 |
| `option_amount` | DECIMAL(12, 0) |  | X | `0` | 옵션 금액 합계 |
| `total_amount` | DECIMAL(12, 0) |  | X | 없음 | 항목 총액 |
| `requirements` | TEXT |  | O | NULL | 항목 요청사항 |
| `preparation_days` | SMALLINT UNSIGNED |  | X | `0` | 준비 기간 스냅샷 |
| `stock_deducted_at` | DATETIME(6) |  | O | NULL | 재고 차감 시각 |
| `stock_restored_at` | DATETIME(6) |  | O | NULL | 재고 복구 시각 |

- FK: `fk_order_items_order` — `order_id` → `orders.id`
- FK: `fk_order_items_product` — `product_id` → `products.id`
- CHECK: `product_type IN ('GENERAL', 'CUSTOM')`
- CHECK: `quantity > 0`, 금액 컬럼은 모두 0 이상
- CHECK: `stock_restored_at`이 있으면 `stock_deducted_at`도 있어야 한다.

## `order_item_options`

주문 항목의 선택 옵션과 가격 스냅샷을 저장한다.

| 컬럼 | 타입 | 키 | Null | 기본값 | 의미 |
|---|---|---|---|---|---|
| `id` | BIGINT | PK | X | AUTO_INCREMENT | 주문 옵션 식별자 |
| `order_item_id` | BIGINT | FK, UK | X | 없음 | 주문 항목 식별자 |
| `product_option_id` | BIGINT | FK, UK | X | 없음 | 상품 옵션 식별자 |
| `option_group_name` | VARCHAR(100) |  | X | 없음 | 옵션 그룹명 스냅샷 |
| `option_name` | VARCHAR(100) |  | X | 없음 | 옵션명 스냅샷 |
| `additional_price` | DECIMAL(12, 0) |  | X | `0` | 추가 금액 스냅샷 |

- UK: `uk_order_item_options_item_option` (`order_item_id`, `product_option_id`)
- FK: `order_item_id` → `order_items.id`, `product_option_id` → `product_options.id`
- CHECK: `additional_price >= 0`

## `order_item_images`

주문 항목 이미지 스냅샷을 저장한다.

| 컬럼 | 타입 | 키 | Null | 기본값 | 의미 |
|---|---|---|---|---|---|
| `id` | BIGINT | PK | X | AUTO_INCREMENT | 이미지 식별자 |
| `order_item_id` | BIGINT | FK | X | 없음 | 주문 항목 식별자 |
| `image_url` | VARCHAR(500) |  | X | 없음 | 이미지 URL |
| `sort_order` | INT |  | X | `0` | 노출 순서 |

- FK: `fk_order_item_images_item` — `order_item_id` → `order_items.id`
- CHECK: `sort_order >= 0`

## `order_cart_items`

장바구니에서 생성한 주문이 결제 완료된 뒤, 해당 주문에 포함됐던 장바구니 항목만 정리하기 위한 연결 테이블이다.
결제 대기·실패·만료 주문에서는 장바구니를 유지한다. 결제 완료 트랜잭션에서 발행한 이벤트를 cart 도메인의
리스너가 `AFTER_COMMIT` 단계에서 받은 뒤, 이 연결 정보를 조회해 공개 Command로 항목을 멱등 삭제한다.

| 컬럼 | 타입 | 키 | Null | 기본값 | 의미 |
|---|---|---|---|---|---|
| `order_id` | BIGINT | PK, FK | X | 없음 | 주문 식별자 |
| `cart_item_id` | BIGINT | PK | X | 없음 | 결제 완료 후 정리할 장바구니 항목 식별자 |
| `snapshot_quantity` | INT UNSIGNED | - | X | 1 | 주문 생성 시점 장바구니 항목 수량 |

- PK: (`order_id`, `cart_item_id`)
- FK: `fk_order_cart_items_order` (`order_id`) → `orders.id`
- `cart_item_id`에는 FK를 두지 않는다. 결제 전 사용자가 장바구니 항목을 직접 삭제해도 주문 생성 이력과
  결제 처리가 막히지 않도록 하며, 결제 후 정리는 삭제 행 수와 관계없이 멱등 처리한다.

## 관련 migration

- `V0__initial_schema.sql`
- `V20260729_184356__align_order_payment_schema.sql`
- `V20260802_185811__track_order_item_stock_restore.sql`
- `V20260803_183257__add_order_request_key.sql`
- `V20260810_200833__add_statistics_source_indexes.sql`
- `V20260811_145723__add_order_in_production_status.sql`
- `V20260811_165915__add_custom_production_due_index.sql`
- `V20260812_115115__add_order_cart_item_links.sql`
- `V20260812_155402__add_order_cart_item_snapshot_quantity.sql`
- `V20260818_104741__remove_unused_order_schema.sql`
- `V20260819_115236__add_order_pending_payment_lookup_index.sql`

> `order_cart_items.snapshot_quantity`는 주문 생성 당시 장바구니 수량이다. 결제 후 정리 시 현재 수량과 비교하여, 수량이 변경된 장바구니 항목은 삭제하지 않는다.

## 장바구니 선택 정책

주문 생성 요청의 `cartItemIds`에는 항목 종류 개수 상한이 없다. 다만 각 항목의 수량은 상품별 판매 수량·재고 정책을 따른다.
