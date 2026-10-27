-- add_payment_provider
-- 생성: 2026-10-26 19:51:17
--
-- 규칙
-- * 이 파일은 공유된 뒤 수정하지 않는다. 변경이 필요하면 새 migration 을 만든다.
-- * 로컬 샘플 데이터는 여기 넣지 않는다. db/seed/seed-local.sql 을 쓴다.
-- * 한 migration 은 하나의 배포 가능한 스키마 전환을 담는다. SQL 수만으로 나누거나 합치지 않는다.

-- 자체 PG 전환 전 생성된 READY·DONE·만료·취소 결제는 모두 Toss 기록이다.
-- 기본값은 구버전의 INSERT와 함께 배포되는 동안 기존 Toss 기록을 보존한다.
-- 신규 애플리케이션은 provider를 명시하며 설정 변경으로 기존 행을 덮어쓰지 않는다.
-- 컬럼과 CHECK를 한 ALTER에 묶어 중간 스키마가 남지 않도록 한다.
ALTER TABLE payments
    ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'TOSS' AFTER order_id,
    ADD CONSTRAINT chk_payments_provider CHECK (provider IN ('TOSS', 'OWN'));
