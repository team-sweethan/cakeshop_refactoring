-- add_order_gateway_review_proceed
-- 생성: 2026-11-04 12:42:19
--
-- 규칙
-- * 이 파일은 공유된 뒤 수정하지 않는다. 변경이 필요하면 새 migration 을 만든다.
-- * 로컬 샘플 데이터는 여기 넣지 않는다. db/seed/seed-local.sql 을 쓴다.
-- * 한 migration 은 하나의 배포 가능한 스키마 전환을 담는다. SQL 수만으로 나누거나 합치지 않는다.


-- PG(돈워리)가 결제를 확인 중(payments.gateway_held_at 있고 gateway_captured_at 없음)인 주문을
-- 가맹점 관리자가 판정 전에 진행(제작 시작 · 픽업 준비 · 픽업 완료 등)한 기록이다 (DonWorry ADR-0064 4절 · 4-1).
-- 진행 뒤 PG가 거절하면 손실은 가맹점 부담이며, 확인 창에 동의하고 처음 진행한 관리자와 시각만 남긴다.
-- 기존 주문은 모두 NULL(판정 전 진행 없음)이다.
ALTER TABLE `orders`
    ADD COLUMN `review_proceeded_by` BIGINT NULL AFTER `picked_up_by`,
    ADD COLUMN `review_proceeded_at` DATETIME(6) NULL AFTER `review_proceeded_by`,
    ADD CONSTRAINT `fk_orders_review_proceeded_by`
        FOREIGN KEY (`review_proceeded_by`) REFERENCES `members` (`id`);
