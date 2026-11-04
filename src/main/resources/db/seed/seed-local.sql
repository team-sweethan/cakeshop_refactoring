-- 로컬 개발 환경 전용 시드 데이터
--
-- ⚠️ 주의: 이 스크립트는 대표 매장·영업시간을 제외한 로컬 애플리케이션 데이터를 지우고
--    샘플 데이터를 다시 넣는다. 로컬에서 만든 주문·리뷰·게시글도 함께 사라진다.
--    운영/공용 DB에서는 절대 실행하지 않는다.
--
-- Flyway 관리 대상이 아니다. flyway_schema_history 에 기록이 남지 않으며,
-- 스키마 마이그레이션(db/migration)이 모두 적용된 뒤 수동으로 실행한다.
--   1) gradlew bootRun --args="--spring.profiles.active=local"   → 스키마
--   2) MariaDB 클라이언트에서 이 파일 실행                          → 샘플 데이터
-- 내용을 고친 뒤에는 2)만 다시 실행하면 된다. DB 재생성이 필요 없다.

-- ---------------------------------------------------------------------------
-- 0. 초기화 — 몇 번이든 다시 실행할 수 있도록 기존 데이터를 지운다.
--
-- FOREIGN_KEY_CHECKS 를 끄지 않는다. 대신 V0__initial_schema.sql 의 FK 정의를
-- 역순으로 따라 자식 → 부모 순으로 지운다. FK 를 우회하지 않으므로 고아 행이 남지 않는다.
-- flyway_schema_history 는 건드리지 않는다 (스키마 이력은 그대로 유지).
-- ---------------------------------------------------------------------------

-- comments 는 parent_comment_id 로 자기 자신을 참조한다.
-- 중첩 깊이와 무관하게 안전하도록 부모 참조를 먼저 끊고 지운다.
UPDATE `comments` SET `parent_comment_id` = NULL;

DELETE FROM `notification_deliveries`;
DELETE FROM `notifications`;
DELETE FROM `customer_admin_notes`;
DELETE FROM `chat_room_read_cursors`;
DELETE FROM `chat_message_attachments`;
DELETE FROM `chat_room_orders`;
DELETE FROM `chat_messages`;
DELETE FROM `chat_rooms`;
DELETE FROM `member_coupons`;
DELETE FROM `coupons`;
DELETE FROM `post_reports`;
-- daily_popular_posts 도 posts 를 FK 로 참조한다(조각 7b). 배치가 한 번이라도 돈
-- 뒤에는 이 줄이 없으면 아래 DELETE FROM posts 가 제약에 걸린다.
DELETE FROM `daily_popular_posts`;
-- 실행 기록에는 FK 가 없지만 함께 지운다. 남겨 두면 시드로 글을 새로 깔아도 배치가
-- "이미 확정한 날짜" 로 판단해 건너뛰어서(D4), 인기글이 영원히 안 채워진다.
DELETE FROM `popular_post_batch_runs`;
-- post_views 는 posts 를 FK 로 참조한다(조각 6). 여기서 지우지 않으면 아래
-- DELETE FROM posts 가 제약에 걸려 시드를 두 번째 실행할 때부터 통째로 실패한다.
DELETE FROM `post_views`;
DELETE FROM `post_images`;
DELETE FROM `post_likes`;
-- 답글이 뿌리를 참조하므로(fk_comments_parent) 답글을 먼저 지운다.
DELETE FROM `comments` WHERE `parent_comment_id` IS NOT NULL;
DELETE FROM `comments`;
DELETE FROM `posts`;
DELETE FROM `post_categories`;
-- community_notices 는 posts 가 아니라 members 를 FK 로 참조한다(조각 14a). 게시글과
-- 함께 두는 이유는 지우는 순서가 아니라 도메인이 같아서다 — 이 줄이 없으면 공지가 한 건이라도
-- 있는 DB 에서 아래 DELETE FROM members 가 제약에 걸려 시드가 통째로 실패한다.
DELETE FROM `community_notices`;
DELETE FROM `review_replies`;
DELETE FROM `review_images`;
DELETE FROM `reviews`;
DELETE FROM `payment_cancellations`;
DELETE FROM `payments`;
DELETE FROM `order_item_images`;
DELETE FROM `order_item_options`;
DELETE FROM `order_items`;
DELETE FROM `orders`;
DELETE FROM `cart_item_images`;
DELETE FROM `cart_item_options`;
DELETE FROM `cart_items`;
DELETE FROM `carts`;
DELETE FROM `product_images`;
DELETE FROM `product_options`;
DELETE FROM `product_option_groups`;
DELETE FROM `products`;
DELETE FROM `categories`;
DELETE FROM `store_holiday`;
DELETE FROM `social_accounts`;
DELETE FROM `member_status_histories`;
DELETE FROM `members`;

-- DELETE 는 AUTO_INCREMENT 카운터를 되돌리지 않는다. 그대로 두면 재실행할 때마다
-- 상품 id 가 밀려 /products/1 같은 경로가 깨진다. 매번 같은 id 가 나오도록 되돌린다.
ALTER TABLE `notification_deliveries` AUTO_INCREMENT = 1;
ALTER TABLE `notifications` AUTO_INCREMENT = 1;
ALTER TABLE `customer_admin_notes` AUTO_INCREMENT = 1;
ALTER TABLE `chat_room_read_cursors` AUTO_INCREMENT = 1;
ALTER TABLE `chat_message_attachments` AUTO_INCREMENT = 1;
ALTER TABLE `chat_messages` AUTO_INCREMENT = 1;
ALTER TABLE `chat_room_orders` AUTO_INCREMENT = 1;
ALTER TABLE `chat_rooms` AUTO_INCREMENT = 1;
ALTER TABLE `member_coupons` AUTO_INCREMENT = 1;
ALTER TABLE `coupons` AUTO_INCREMENT = 1;
ALTER TABLE `post_reports` AUTO_INCREMENT = 1;
ALTER TABLE `post_views` AUTO_INCREMENT = 1;
ALTER TABLE `post_images` AUTO_INCREMENT = 1;
ALTER TABLE `post_likes` AUTO_INCREMENT = 1;
ALTER TABLE `comments` AUTO_INCREMENT = 1;
ALTER TABLE `posts` AUTO_INCREMENT = 1;
ALTER TABLE `post_categories` AUTO_INCREMENT = 1;
ALTER TABLE `review_replies` AUTO_INCREMENT = 1;
ALTER TABLE `review_images` AUTO_INCREMENT = 1;
ALTER TABLE `reviews` AUTO_INCREMENT = 1;
ALTER TABLE `payment_cancellations` AUTO_INCREMENT = 1;
ALTER TABLE `payments` AUTO_INCREMENT = 1;
ALTER TABLE `order_item_images` AUTO_INCREMENT = 1;
ALTER TABLE `order_item_options` AUTO_INCREMENT = 1;
ALTER TABLE `order_items` AUTO_INCREMENT = 1;
ALTER TABLE `orders` AUTO_INCREMENT = 1;
ALTER TABLE `cart_item_images` AUTO_INCREMENT = 1;
ALTER TABLE `cart_item_options` AUTO_INCREMENT = 1;
ALTER TABLE `cart_items` AUTO_INCREMENT = 1;
ALTER TABLE `carts` AUTO_INCREMENT = 1;
ALTER TABLE `product_images` AUTO_INCREMENT = 1;
ALTER TABLE `product_options` AUTO_INCREMENT = 1;
ALTER TABLE `product_option_groups` AUTO_INCREMENT = 1;
ALTER TABLE `products` AUTO_INCREMENT = 1;
ALTER TABLE `categories` AUTO_INCREMENT = 1;
ALTER TABLE `store_holiday` AUTO_INCREMENT = 1;
ALTER TABLE `social_accounts` AUTO_INCREMENT = 1;
ALTER TABLE `member_status_histories` AUTO_INCREMENT = 1;
ALTER TABLE `members` AUTO_INCREMENT = 1;

-- ---------------------------------------------------------------------------
-- 1. 기본 로컬 계정
-- ---------------------------------------------------------------------------
-- 공통 샘플 계정: 비밀번호는 둘 다 'Admin1234!' (BCrypt 해시 저장)
-- role 은 접두어 없는 값(USER/ADMIN)으로 저장 (MemberDetailsService 가 'ROLE_' 부착)
-- name 은 V3__add_member_name.sql 에서 NOT NULL 이 됐다. 시드가 마이그레이션 뒤에 돌므로
-- 여기서 직접 채워야 한다. 값은 V3 의 보정 UPDATE(name = nickname)와 같게 맞춘다.
INSERT INTO `members` (`email`, `password`, `name`, `nickname`, `phone`, `role`, `status`) VALUES
    ('admin@cakeshop.local',
    '$2a$10$wRIE78x8sm..uLtbp9LHde7l6wUWQD3NjPvThQaXvZ3PpXfW6wwX.',
    '관리자', '관리자', '010-0000-0001', 'ADMIN', 'ACTIVE'),
    ('user@cakeshop.local',
    '$2a$10$wRIE78x8sm..uLtbp9LHde7l6wUWQD3NjPvThQaXvZ3PpXfW6wwX.',
    '테스트회원', '테스트회원', '010-0000-0002', 'USER', 'ACTIVE');

-- =========================================================
-- 상품 도메인 로컬 테스트 데이터
-- 공용 RDS 적용 금지
-- =========================================================

-- 테스트용 케이크 카테고리를 등록한다.
-- 동일한 code가 존재하면 중복 행을 만들지 않고 기존 값을 갱신한다.
INSERT INTO `categories` (
    `code`,
    `name`,
    `sort_order`,
    `is_active`
)
VALUES (
    'CAKE',
    '케이크',
    1,
    1
)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `sort_order` = VALUES(`sort_order`),
    `is_active` = VALUES(`is_active`);


-- =========================================================
-- 다양한 케이크 테스트 상품 등록
--
-- 동일한 카테고리와 상품명이 존재하면 다시 등록하지 않는다.
-- =========================================================

INSERT INTO `products` (
    `category_id`,
    `name`,
    `description`,
    `base_price`,
    `stock_quantity`,
    `product_type`,
    `preparation_days`,
    `status`
)
SELECT
    c.`id`,
    sample.`name`,
    sample.`description`,
    sample.`base_price`,
    sample.`stock_quantity`,
    sample.`product_type`,
    sample.`preparation_days`,
    sample.`status`
FROM `categories` c
CROSS JOIN (
    -- 일반 상품은 크기·색상 조합별로 독립적인 재고를 가진다.
    SELECT
        '딸기 생크림 케이크 1호' AS `name`,
        '신선한 딸기와 부드러운 생크림으로 만든 1호 케이크입니다.' AS `description`,
        35000 AS `base_price`,
        7 AS `stock_quantity`,
        'GENERAL' AS `product_type`,
        0 AS `preparation_days`,
        'ACTIVE' AS `status`

    UNION ALL

    SELECT
        '딸기 생크림 케이크 2호',
        '신선한 딸기와 부드러운 생크림으로 만든 2호 케이크입니다.',
        45000,
        5,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    SELECT
        '딸기 생크림 케이크 3호',
        '신선한 딸기와 부드러운 생크림으로 만든 3호 케이크입니다.',
        55000,
        2,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    SELECT
        '초코 가나슈 케이크 1호 다크초코',
        '진한 다크초콜릿 가나슈로 만든 1호 케이크입니다.',
        42000,
        6,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    SELECT
        '초코 가나슈 케이크 1호 화이트',
        '부드러운 화이트초콜릿 가나슈로 만든 1호 케이크입니다.',
        42000,
        4,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    SELECT
        '초코 가나슈 케이크 2호 다크초코',
        '진한 다크초콜릿 가나슈로 만든 2호 케이크입니다.',
        54000,
        3,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    SELECT
        '초코 가나슈 케이크 2호 화이트',
        '부드러운 화이트초콜릿 가나슈로 만든 2호 케이크입니다.',
        54000,
        2,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 말차 케이크: 재고가 적은 상품
    SELECT
        '말차 팥 케이크',
        '쌉싸름한 제주 말차 크림과 달콤한 팥을 조합한 케이크입니다.',
        39000,
        3,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 얼그레이 케이크: 품절 상태 확인용 상품
    SELECT
        '얼그레이 오렌지 케이크',
        '향긋한 얼그레이 크림과 상큼한 오렌지를 더한 케이크입니다.',
        40000,
        0,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 당근 케이크: 일반 재고 상품
    SELECT
        '호두 당근 케이크',
        '당근과 호두를 넣고 크림치즈 프로스팅을 올린 케이크입니다.',
        32000,
        10,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 주문 제작 케이크: 재고 제한 없음
    SELECT
        '레터링 생크림 케이크',
        '원하는 문구와 색상을 선택할 수 있는 주문 제작 케이크입니다.',
        55000,
        NULL,
        'CUSTOM',
        4,
        'ACTIVE'

    UNION ALL

    -- 바닐라 케이크: 일반 재고와 낮은 가격대 확인용
    SELECT
        '바닐라 빈 케이크',
        '마다가스카르산 바닐라 빈을 넣은 부드러운 생크림 케이크입니다.',
        30000,
        20,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 망고 케이크: 일반 상품이 품절된 경우 확인용
    SELECT
        '애플망고 요거트 케이크',
        '애플망고와 산뜻한 요거트 크림을 올린 케이크입니다.',
        45000,
        0,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 티라미수: 중간 가격대와 일반 재고 확인용
    SELECT
        '마스카포네 티라미수 케이크',
        '에스프레소 시트와 마스카포네 크림을 겹겹이 쌓은 케이크입니다.',
        46000,
        5,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 레드벨벳: 재고 1개 경계값 확인용
    SELECT
        '레드벨벳 크림치즈 케이크',
        '촉촉한 레드벨벳 시트와 진한 크림치즈 프로스팅을 사용했습니다.',
        43000,
        1,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 레몬 치즈케이크: 재고가 넉넉한 일반 상품
    SELECT
        '레몬 바스크 치즈케이크',
        '상큼한 레몬 향을 더해 구운 진한 바스크 치즈케이크입니다.',
        36000,
        15,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 흑임자 케이크: 재고 부족 상태 확인용
    SELECT
        '흑임자 인절미 케이크',
        '고소한 흑임자 크림과 쫄깃한 인절미를 조합한 케이크입니다.',
        41000,
        2,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 복숭아 케이크: 상품명 검색과 일반 재고 확인용
    SELECT
        '복숭아 우유 생크림 케이크',
        '달콤한 복숭아와 담백한 우유 생크림을 사용한 케이크입니다.',
        44000,
        7,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 밤 케이크: 일반 상품 품절 필터 확인용
    SELECT
        '몽블랑 밤 케이크',
        '국산 밤 페이스트와 부드러운 생크림을 층층이 올린 케이크입니다.',
        48000,
        0,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 미니 케이크: 최저 가격대 확인용
    SELECT
        '미니 도시락 케이크',
        '한두 명이 가볍게 즐기기 좋은 작은 크기의 케이크입니다.',
        18000,
        25,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 10만원 상품: 기본 최대 가격 경계값 포함 확인용
    SELECT
        '프리미엄 3단 기념일 케이크',
        '특별한 기념일을 위한 화려한 3단 케이크입니다.',
        100000,
        1,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 10만원 초과 상품: 기본 최대 가격 필터 제외 확인용
    SELECT
        '럭셔리 웨딩 케이크',
        '웨딩 행사를 위한 대형 수제 장식 케이크입니다.',
        120000,
        NULL,
        'CUSTOM',
        14,
        'ACTIVE'

    UNION ALL

    -- 포토 케이크: 주문 제작과 무제한 재고 확인용
    SELECT
        '포토 이미지 주문 제작 케이크',
        '고객이 전달한 사진을 식용 이미지로 제작하는 맞춤 케이크입니다.',
        62000,
        NULL,
        'CUSTOM',
        4,
        'ACTIVE'

    UNION ALL

    -- 캐릭터 케이크: 주문 제작 검색 및 인기순 확인용
    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '원하는 캐릭터를 입체 장식으로 표현하는 주문 제작 케이크입니다.',
        75000,
        NULL,
        'CUSTOM',
        5,
        'ACTIVE'

    UNION ALL

    -- 2단 주문 제작: 높은 가격대의 주문 제작 상품
    SELECT
        '2단 파티 주문 제작 케이크',
        '여러 명이 함께 즐길 수 있는 2단 구성의 파티용 맞춤 케이크입니다.',
        90000,
        NULL,
        'CUSTOM',
        7,
        'ACTIVE'

    UNION ALL

    -- 돌잔치 케이크: 긴 준비 기간 확인용
    SELECT
        '돌잔치 한복 주문 제작 케이크',
        '한복과 전통 문양을 표현한 돌잔치용 주문 제작 케이크입니다.',
        85000,
        NULL,
        'CUSTOM',
        6,
        'ACTIVE'

    UNION ALL

    -- 반려동물 케이크: 키워드 검색 확인용
    SELECT
        '반려동물 초상화 주문 제작 케이크',
        '반려동물 사진을 바탕으로 얼굴을 그려 넣는 맞춤 케이크입니다.',
        68000,
        NULL,
        'CUSTOM',
        4,
        'ACTIVE'

    UNION ALL

    -- 기업 행사 케이크: 주문 제작 상품의 다양한 가격 확인용
    SELECT
        '기업 로고 주문 제작 케이크',
        '기업 로고와 행사 문구를 반영하는 단체 행사 주문 제작 케이크입니다.',
        78000,
        NULL,
        'CUSTOM',
        3,
        'ACTIVE'

    UNION ALL

    -- 주문 제작 품절: CUSTOM 유형에도 재고가 지정된 예외 상황 확인용
    SELECT
        '플라워 데코 주문 제작 케이크',
        '수제 플라워 장식을 올리는 기간 한정 주문 제작 케이크입니다.',
        72000,
        0,
        'CUSTOM',
        5,
        'ACTIVE'

    UNION ALL

    -- 쌀 케이크: 일반 상품 검색 확인용
    SELECT
        '백설기 생화 케이크',
        '담백한 백설기 위에 생화 느낌의 장식을 올린 케이크입니다.',
        52000,
        6,
        'GENERAL',
        0,
        'ACTIVE'

    UNION ALL

    -- 판매 중지 상품: 고객 화면에 노출되지 않아야 한다.
    SELECT
        '블루베리 요거트 케이크',
        '블루베리와 요거트 크림을 사용한 상큼한 케이크입니다.',
        37000,
        6,
        'GENERAL',
        0,
        'INACTIVE'

    UNION ALL

    -- 두 번째 판매 중지 상품: 필터와 고객 비노출 확인용
    SELECT
        '단종 체리 초콜릿 케이크',
        '판매가 종료되어 고객 상품 목록에 노출되지 않는 케이크입니다.',
        47000,
        4,
        'GENERAL',
        0,
        'INACTIVE'
) sample
WHERE c.`code` = 'CAKE'
  AND NOT EXISTS (
      SELECT 1
      FROM `products` p
      WHERE p.`category_id` = c.`id`
        AND p.`name` = sample.`name`
  );

-- =========================================================
-- 상품 상세 화면 옵션 그룹
--
-- 상품명과 옵션 그룹명이 같은 데이터가 있으면 중복 등록하지 않는다.
-- =========================================================

INSERT INTO `product_option_groups` (
    `product_id`,
    `name`,
    `required`,
    `selection_type`,
    `sort_order`
)
SELECT
    p.`id`,
    sample.`group_name`,
    sample.`required`,
    sample.`selection_type`,
    sample.`sort_order`
FROM `products` p
INNER JOIN (
    SELECT
        '레터링 생크림 케이크' AS `product_name`,
        '케이크 크기' AS `group_name`,
        1 AS `required`,
        'SINGLE' AS `selection_type`,
        1 AS `sort_order`

    UNION ALL

    SELECT
        '레터링 생크림 케이크',
        '크림 색상',
        1,
        'SINGLE',
        2

    UNION ALL

    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '케이크 크기',
        1,
        'SINGLE',
        1

    UNION ALL

    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '추가 장식',
        0,
        'MULTIPLE',
        2
) sample
        ON sample.`product_name` = p.`name`
WHERE NOT EXISTS (
    SELECT 1
    FROM `product_option_groups` pog
    WHERE pog.`product_id` = p.`id`
      AND pog.`name` = sample.`group_name`
);


-- =========================================================
-- 상품 상세 화면 개별 옵션
--
-- 같은 옵션 그룹 안에 같은 옵션명이 있으면 중복 등록하지 않는다.
-- =========================================================

INSERT INTO `product_options` (
    `option_group_id`,
    `name`,
    `additional_price`,
    `status`,
    `sort_order`
)
SELECT
    pog.`id`,
    sample.`option_name`,
    sample.`additional_price`,
    sample.`status`,
    sample.`sort_order`
FROM `products` p
INNER JOIN `product_option_groups` pog
        ON pog.`product_id` = p.`id`
INNER JOIN (
    SELECT
        '레터링 생크림 케이크' AS `product_name`,
        '케이크 크기' AS `group_name`,
        '1호' AS `option_name`,
        0 AS `additional_price`,
        'ACTIVE' AS `status`,
        1 AS `sort_order`

    UNION ALL

    SELECT
        '레터링 생크림 케이크',
        '케이크 크기',
        '2호',
        15000,
        'ACTIVE',
        2

    UNION ALL

    SELECT
        '레터링 생크림 케이크',
        '크림 색상',
        '화이트',
        0,
        'ACTIVE',
        1

    UNION ALL

    SELECT
        '레터링 생크림 케이크',
        '크림 색상',
        '핑크',
        0,
        'ACTIVE',
        2

    UNION ALL

    SELECT
        '레터링 생크림 케이크',
        '크림 색상',
        '하늘색',
        0,
        'ACTIVE',
        3

    UNION ALL

    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '케이크 크기',
        '2호',
        0,
        'ACTIVE',
        1

    UNION ALL

    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '케이크 크기',
        '3호',
        18000,
        'ACTIVE',
        2

    UNION ALL

    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '추가 장식',
        '별 장식',
        3000,
        'ACTIVE',
        1

    UNION ALL

    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '추가 장식',
        '하트 장식',
        3000,
        'ACTIVE',
        2

    UNION ALL

    -- 비활성 옵션이 고객 상세에 노출되지 않는지 확인한다.
    SELECT
        '캐릭터 입체 주문 제작 케이크',
        '추가 장식',
        '단종 왕관 장식',
        5000,
        'INACTIVE',
        3
) sample
        ON sample.`product_name` = p.`name`
       AND sample.`group_name` = pog.`name`
WHERE NOT EXISTS (
    SELECT 1
    FROM `product_options` po
    WHERE po.`option_group_id` = pog.`id`
      AND po.`name` = sample.`option_name`
);


-- =========================================================
-- 상품 이미지
--
-- 파일은 src/main/resources/static/images/products/<폴더>/01.jpg~05.jpg 에 있다.
-- 01은 대표 이미지, 02~05는 단면·상단 장식·질감·연출 상세 이미지다.
-- 업로드 저장소가 아닌 정적 경로라 관리자 화면에서 이미지를 지워도 파일은 남는다.
-- 크기만 다른 상품(딸기 생크림 2·3호, 초코 가나슈 2호)은 1호 이미지를 함께 쓴다.
-- =========================================================

INSERT INTO `product_images` (
    `product_id`,
    `image_url`,
    `sort_order`
)
SELECT
    p.`id`,
    CONCAT('/images/products/', sample.`image_folder`, '/0', image_slot.`image_number`, '.jpg'),
    image_slot.`image_number` - 1
FROM `products` p
INNER JOIN `categories` c
        ON c.`id` = p.`category_id`
       AND c.`code` = 'CAKE'
INNER JOIN (
    SELECT
        '딸기 생크림 케이크 1호' AS `product_name`,
        'strawberry-cream' AS `image_folder`
    UNION ALL SELECT '딸기 생크림 케이크 2호', 'strawberry-cream'
    UNION ALL SELECT '딸기 생크림 케이크 3호', 'strawberry-cream'
    UNION ALL SELECT '초코 가나슈 케이크 1호 다크초코', 'chocolate-ganache-dark'
    UNION ALL SELECT '초코 가나슈 케이크 1호 화이트', 'chocolate-ganache-white'
    UNION ALL SELECT '초코 가나슈 케이크 2호 다크초코', 'chocolate-ganache-dark'
    UNION ALL SELECT '초코 가나슈 케이크 2호 화이트', 'chocolate-ganache-white'
    UNION ALL SELECT '말차 팥 케이크', 'matcha-red-bean'
    UNION ALL SELECT '얼그레이 오렌지 케이크', 'earl-grey-orange'
    UNION ALL SELECT '호두 당근 케이크', 'walnut-carrot'
    UNION ALL SELECT '레터링 생크림 케이크', 'custom-lettering-cream'
    UNION ALL SELECT '바닐라 빈 케이크', 'vanilla-bean'
    UNION ALL SELECT '애플망고 요거트 케이크', 'apple-mango-yogurt'
    UNION ALL SELECT '마스카포네 티라미수 케이크', 'mascarpone-tiramisu'
    UNION ALL SELECT '레드벨벳 크림치즈 케이크', 'red-velvet-cream-cheese'
    UNION ALL SELECT '레몬 바스크 치즈케이크', 'lemon-basque-cheesecake'
    UNION ALL SELECT '흑임자 인절미 케이크', 'black-sesame-injeolmi'
    UNION ALL SELECT '복숭아 우유 생크림 케이크', 'peach-milk-cream'
    UNION ALL SELECT '몽블랑 밤 케이크', 'mont-blanc-chestnut'
    UNION ALL SELECT '미니 도시락 케이크', 'mini-lunchbox'
    UNION ALL SELECT '프리미엄 3단 기념일 케이크', 'premium-three-tier-anniversary'
    UNION ALL SELECT '럭셔리 웨딩 케이크', 'custom-luxury-wedding'
    UNION ALL SELECT '포토 이미지 주문 제작 케이크', 'custom-photo'
    UNION ALL SELECT '캐릭터 입체 주문 제작 케이크', 'custom-character'
    UNION ALL SELECT '2단 파티 주문 제작 케이크', 'custom-two-tier-party'
    UNION ALL SELECT '돌잔치 한복 주문 제작 케이크', 'custom-dol-hanbok'
    UNION ALL SELECT '반려동물 초상화 주문 제작 케이크', 'custom-pet-portrait'
    UNION ALL SELECT '기업 로고 주문 제작 케이크', 'custom-corporate-logo'
    UNION ALL SELECT '플라워 데코 주문 제작 케이크', 'custom-flower-deco'
    UNION ALL SELECT '백설기 생화 케이크', 'baekseolgi-flower'
    UNION ALL SELECT '블루베리 요거트 케이크', 'blueberry-yogurt'
    UNION ALL SELECT '단종 체리 초콜릿 케이크', 'cherry-chocolate'
) sample
        ON sample.`product_name` = p.`name`
CROSS JOIN (
    SELECT 1 AS `image_number`
    UNION ALL SELECT 2
    UNION ALL SELECT 3
    UNION ALL SELECT 4
    UNION ALL SELECT 5
) image_slot
ORDER BY p.`id`, image_slot.`image_number`;


-- =========================================================
-- 대표 매장 사진
--
-- 대표 매장은 migration(provision_default_store)이 만든다. 매장 정보는 지우거나 덮어쓰지 않고
-- 사진이 비어 있을 때만 정적 파일 경로를 채운다. 관리자가 올린 사진은 그대로 둔다.
-- 파일은 src/main/resources/static/images/home/store.jpg 에 있다.
-- =========================================================

UPDATE `store`
   SET `image_url` = '/images/home/store.jpg'
 WHERE `id` = 1
   AND (`image_url` IS NULL OR `image_url` = '');
