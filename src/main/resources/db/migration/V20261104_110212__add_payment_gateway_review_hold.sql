-- add_payment_gateway_review_hold
-- 생성: 2026-11-04 11:02:12
--
-- 규칙
-- * 이 파일은 공유된 뒤 수정하지 않는다. 변경이 필요하면 새 migration 을 만든다.
-- * 로컬 샘플 데이터는 여기 넣지 않는다. db/seed/seed-local.sql 을 쓴다.
-- * 한 migration 은 하나의 배포 가능한 스키마 전환을 담는다. SQL 수만으로 나누거나 합치지 않는다.


-- 돈워리(자체 PG)가 카드 승인 뒤 매입을 보류(HELD)하고 보안 직원 판정을 기다리는 결제를 표시한다
-- (DonWorry ADR-0064 4절). 구매자에게는 결제 완료, 가맹점에는 "결제 확인 중"으로 보인다.
-- * gateway_held_at: 보류 상태로 결제를 완료한 시각. 보류가 아니었던 결제는 NULL이다.
-- * gateway_captured_at: 직원 승인으로 매입(CAPTURED)이 확인된 시각. 판정 전이면 NULL이다.
-- 판정 대기 = status 'DONE' 이고 gateway_held_at 은 있고 gateway_captured_at 은 NULL.
-- 직원 거절(VOIDED)은 기존처럼 결제를 CANCELED(failure_code GATEWAY_VOIDED)로 바꾸므로 따로 컬럼을 두지 않는다.
-- 기존 결제는 모두 NULL(보류 아님)이라 기존 동작이 그대로다.
ALTER TABLE payments
    ADD COLUMN gateway_held_at DATETIME(6) NULL AFTER approved_at,
    ADD COLUMN gateway_captured_at DATETIME(6) NULL AFTER gateway_held_at;
