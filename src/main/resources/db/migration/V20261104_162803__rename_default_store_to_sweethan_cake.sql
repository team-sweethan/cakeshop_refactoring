-- rename_default_store_to_sweethan_cake
-- 생성: 2026-11-04 16:28:03
--
-- 규칙
-- * 이 파일은 공유된 뒤 수정하지 않는다. 변경이 필요하면 새 migration 을 만든다.
-- * 로컬 샘플 데이터는 여기 넣지 않는다. db/seed/seed-local.sql 을 쓴다.
-- * 한 migration 은 하나의 배포 가능한 스키마 전환을 담는다. SQL 수만으로 나누거나 합치지 않는다.


-- 서비스 이름을 "스윗한 케이크"로 통일한다. 화면 헤더·탭 제목은 고정 문구로 바꾸고,
-- 푸터에 보이는 매장 이름은 store.name 이라 여기서 맞춘다.
-- provision_default_store 의 기본값('케이크 공방') 그대로인 대표 매장만 바꾼다.
-- 운영자가 관리자 화면에서 이미 바꾼 이름은 덮어쓰지 않는다.
UPDATE `store`
   SET `name` = '스윗한 케이크'
 WHERE `id` = 1
   AND `name` = '케이크 공방';
