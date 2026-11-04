-- 실제 매장 공지 seed (로컬·공용 개발·시연 DB 공통)
--
-- 고객 화면과 홈에 보일 실제 운영 공지를 넣는다. 작성자는 관리자 계정이다.
-- 기존 데이터를 지우지 않는다. 같은 제목의 공지가 이미 있으면(삭제된 공지 포함) 다시 넣지 않는다.
-- 그래서 여러 번 실행해도 결과가 같고, 관리자가 화면에서 고친 공지도 덮어쓰지 않는다.
-- Flyway 관리 대상이 아니다. migration 을 모두 적용한 뒤 MariaDB 클라이언트로 수동 실행한다.
--
-- 실행 순서
--   로컬: seed-local.sql → (선택) seed-community.sql → seed-notice.sql
--         seed-community.sql 은 공지를 전부 지우고 상태 확인용 공지 5건을 다시 넣으므로
--         이 파일보다 먼저 실행한다.
--   공용 개발·시연 DB: seed-rds-demo.sql(관리자 계정 생성) → seed-notice.sql
--
-- 관리자 계정(admin@cakeshop.local)이 없으면 아무것도 넣지 않는다.
-- 노출 순서는 COALESCE(starts_at, created_at) 내림차순이라 created_at 을 조금씩 달리 둬서
-- 실행할 때마다 같은 순서로 보이게 한다.

SET time_zone = '+09:00';

START TRANSACTION;

INSERT INTO `community_notices`
    (`title`, `content`, `status`, `starts_at`, `ends_at`, `created_by`, `created_at`)
SELECT
    notice.`title`,
    notice.`content`,
    'PUBLISHED',
    notice.`starts_at`,
    notice.`ends_at`,
    admin.`id`,
    NOW(6) - INTERVAL notice.`order_minutes` MINUTE
FROM (
    SELECT
        '돈워리 안전결제 도입 안내' AS `title`,
        '스윗한 케이크에 돈워리 안전결제가 도입되었습니다.\n\n결제 화면에서 결제하기를 누른 뒤 결제 수단으로 "돈워리 안전결제"를 선택하면 결제창이 열립니다. 카드번호는 쇼핑몰에 전달되지 않습니다.\n\n안전한 거래를 위해 일부 결제는 결제가 끝난 뒤 확인 과정을 거칩니다. 이때 주문 상태가 잠시 "주문 확인 중"으로 보일 수 있으며, 확인이 끝나면 자동으로 다음 단계로 진행됩니다. 별도로 하실 일은 없습니다.' AS `content`,
        CAST(NULL AS DATETIME(6)) AS `starts_at`,
        CAST(NULL AS DATETIME(6)) AS `ends_at`,
        0 AS `order_minutes`

    UNION ALL

    SELECT
        '크리스마스·연말 케이크 예약 안내',
        '연말에는 픽업 예약이 몰려 원하는 시간이 일찍 마감될 수 있습니다.\n\n- 12월 24일·25일 픽업은 시간대별 예약이 빠르게 찹니다. 여유 있게 미리 예약해 주세요.\n- 주문 제작 케이크는 제작 준비 기간이 필요하므로 이 기간에는 접수를 일찍 마감할 수 있습니다.\n- 영업시간과 픽업 시간은 화면 아래 매장 안내를 확인해 주세요.\n\n올 한 해 스윗한 케이크를 찾아 주셔서 감사합니다.',
        CAST('2026-11-01 00:00:00' AS DATETIME(6)),
        CAST('2027-01-01 00:00:00' AS DATETIME(6)),
        1

    UNION ALL

    SELECT
        '스윗한 케이크 픽업 이용 안내',
        '주문하신 케이크는 예약한 픽업 시간에 매장 1층 픽업 데스크에서 받으실 수 있습니다.\n\n- 픽업할 때 주문번호 또는 주문자 이름과 전화번호를 알려 주세요.\n- 다른 분이 대신 받으시면 주문할 때 픽업자 정보를 따로 입력해 주세요.\n- 예약 시간보다 늦어질 것 같으면 매장 안내의 전화번호로 미리 연락해 주세요.\n\n생크림 케이크는 신선도를 위해 받으신 뒤 바로 냉장 보관해 주세요.',
        CAST(NULL AS DATETIME(6)),
        CAST(NULL AS DATETIME(6)),
        2

    UNION ALL

    SELECT
        '주문 제작 케이크 접수 안내',
        '레터링, 사진, 캐릭터 등 원하는 디자인으로 케이크를 만들어 드립니다.\n\n- 상품마다 제작 준비 기간이 다릅니다. 상품 화면의 "제작 준비 기간"을 확인한 뒤 픽업 날짜를 골라 주세요.\n- 요청사항은 주문 요청 단계에서 자세히 적어 주세요. 관리자가 확인한 뒤 승인하면 제작을 시작합니다.\n- 승인 전에는 요청 내용을 바꾸거나 전액 취소할 수 있습니다.',
        CAST(NULL AS DATETIME(6)),
        CAST(NULL AS DATETIME(6)),
        3

    UNION ALL

    SELECT
        '주문 취소와 환불 안내',
        '주문 취소 기준을 안내해 드립니다.\n\n- 일반 케이크: 픽업 예정 시각 전까지 전액 취소할 수 있습니다.\n- 주문 제작 케이크: 관리자 승인 전까지 전액 취소할 수 있습니다. 승인 뒤에는 제작이 시작되어 취소가 어렵습니다.\n\n취소는 주문 내역의 주문 상세에서 할 수 있으며, 결제는 결제한 수단으로 전액 취소됩니다.',
        CAST(NULL AS DATETIME(6)),
        CAST(NULL AS DATETIME(6)),
        4
) notice
INNER JOIN `members` admin
        ON admin.`email` = 'admin@cakeshop.local'
       AND admin.`role` = 'ADMIN'
WHERE NOT EXISTS (
    SELECT 1
    FROM `community_notices` existing
    WHERE existing.`title` = notice.`title`
);

COMMIT;
