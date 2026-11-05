-- 시연용 회원·후기 seed (로컬·공용 개발·시연 DB 공통)
--
-- 일반 회원 12명과 그 회원들이 픽업까지 마친 주문 40건, 주문마다 공개 후기 1건을 넣는다.
-- 후기는 상품 12개에 나뉘어 들어가고, 상품의 평균 별점·후기 수를 공개 후기 기준으로 다시 계산한다.
-- 홈 "지금 가장 사랑받는 케이크"와 상품 목록 기본 정렬(후기 수 → 평균 별점)이 이 값으로 정해진다.
--
-- 기존 데이터를 지우지 않는다. 같은 이메일의 회원, 같은 주문번호(DEMO-REVIEW-*)의 주문,
-- 이미 결제·후기가 있는 주문 상품은 다시 넣지 않는다. 그래서 여러 번 실행해도 결과가 같고,
-- 관리자가 숨기거나 지운 후기도 되살리지 않는다.
-- Flyway 관리 대상이 아니다. migration 을 모두 적용한 뒤 MariaDB 클라이언트로 수동 실행한다.
--
-- 실행 순서
--   로컬: seed-local.sql → (선택) seed-community.sql · seed-review.sql · seed-notice.sql → 이 파일
--         seed-local.sql 은 회원·주문·후기를 모두 지우므로 반드시 이 파일보다 먼저 실행한다.
--   공용 개발·시연 DB: seed-rds-demo.sql(관리자 계정·상품 생성) → seed-notice.sql → 이 파일
--
-- 회원 비밀번호는 같은 DB 의 관리자 계정(admin@cakeshop.local) 비밀번호 해시를 그대로 쓴다.
--   로컬은 seed-local.sql 의 관리자 비밀번호와 같고, 공용 DB 는 그 환경의 관리자 비밀번호와 같다.
--   비밀번호 원문과 해시는 이 파일에 넣지 않는다.
-- 관리자 계정이 없으면 아무것도 넣지 않는다.
-- 상품은 seed-rds-demo.sql 과 같은 기준(카테고리 CAKE, 같은 이름이면 가장 작은 id)으로 찾는다.
--
-- 이 파일은 Spring ScriptUtils 로도 실행하므로 문자열 안에 세미콜론을 쓰지 않는다.

SET time_zone = '+09:00';

DROP TEMPORARY TABLE IF EXISTS `_demo_review_members`;
DROP TEMPORARY TABLE IF EXISTS `_demo_review_samples`;
DROP TEMPORARY TABLE IF EXISTS `_demo_review_products`;

CREATE TEMPORARY TABLE `_demo_review_members` (
    `email`      VARCHAR(255) NOT NULL PRIMARY KEY,
    `name`       VARCHAR(50)  NOT NULL,
    `nickname`   VARCHAR(50)  NOT NULL,
    `phone`      VARCHAR(30)  NOT NULL,
    `birth_date` DATE         NOT NULL,
    `created_at` DATETIME(6)  NOT NULL
);

INSERT INTO `_demo_review_members`
    (`email`, `name`, `nickname`, `phone`, `birth_date`, `created_at`)
VALUES
    ('member01@cakeshop.local', '김하은', '딸기러버',   '010-1000-0001', '1996-03-14', '2026-08-01 10:00:00'),
    ('member02@cakeshop.local', '이준호', '주말베이커', '010-1000-0002', '1991-07-02', '2026-08-03 10:00:00'),
    ('member03@cakeshop.local', '박서연', '생크림요정', '010-1000-0003', '1999-11-23', '2026-08-05 10:00:00'),
    ('member04@cakeshop.local', '최민재', '초코홀릭',   '010-1000-0004', '1988-05-30', '2026-08-08 10:00:00'),
    ('member05@cakeshop.local', '정유나', '기념일장인', '010-1000-0005', '1994-09-08', '2026-08-10 10:00:00'),
    ('member06@cakeshop.local', '강도윤', '달콤한하루', '010-1000-0006', '1990-01-17', '2026-08-14 10:00:00'),
    ('member07@cakeshop.local', '조아린', '케이크산책', '010-1000-0007', '2001-06-11', '2026-08-18 10:00:00'),
    ('member08@cakeshop.local', '윤지호', '말차좋아',   '010-1000-0008', '1987-12-04', '2026-08-22 10:00:00'),
    ('member09@cakeshop.local', '장예린', '꾸덕러',     '010-1000-0009', '1997-02-26', '2026-08-29 10:00:00'),
    ('member10@cakeshop.local', '임태현', '아빠케이크', '010-1000-0010', '1985-08-19', '2026-09-05 10:00:00'),
    ('member11@cakeshop.local', '한소율', '레터링덕후', '010-1000-0011', '2000-04-09', '2026-09-08 10:00:00'),
    ('member12@cakeshop.local', '오승우', '디저트탐험', '010-1000-0012', '1993-10-21', '2026-09-20 10:00:00');

-- 주문 1건 = 주문 상품 1개 = 후기 1건.
-- 일반 상품 주문은 픽업 3일 전에, 주문 제작 주문은 7일 전에 들어온 것으로 만든다.
-- 후기는 픽업 후 review_after_hours 시간 뒤에 작성된 것으로 만든다.
CREATE TEMPORARY TABLE `_demo_review_samples` (
    `seq`                SMALLINT     NOT NULL PRIMARY KEY,
    `order_number`       VARCHAR(50)  NOT NULL,
    `member_email`       VARCHAR(255) NOT NULL,
    `product_name`       VARCHAR(100) NOT NULL,
    `overall_rating`     TINYINT      NOT NULL,
    `taste_rating`       TINYINT      NOT NULL,
    `design_rating`      TINYINT      NOT NULL,
    `service_rating`     TINYINT      NOT NULL,
    `content`            TEXT         NOT NULL,
    `picked_up_at`       DATETIME(6)  NOT NULL,
    `review_after_hours` SMALLINT     NOT NULL,
    `requirements`       VARCHAR(255) NULL,
    `request_message`    VARCHAR(255) NULL
);

INSERT INTO `_demo_review_samples`
    (`seq`, `order_number`, `member_email`, `product_name`,
     `overall_rating`, `taste_rating`, `design_rating`, `service_rating`,
     `content`, `picked_up_at`, `review_after_hours`, `requirements`, `request_message`)
VALUES
    -- 딸기 생크림 케이크 1호: 8건, 평균 4.75
    (1, 'DEMO-REVIEW-D01', 'member01@cakeshop.local', '딸기 생크림 케이크 1호', 5, 5, 5, 5,
     '딸기가 큼직하고 생크림이 달지 않아서 부모님이 특히 좋아하셨어요.', '2026-08-16 15:00:00', 3, NULL, NULL),
    (2, 'DEMO-REVIEW-D02', 'member03@cakeshop.local', '딸기 생크림 케이크 1호', 5, 5, 4, 5,
     '시트가 촉촉하고 크림이 가벼워서 둘이서 금방 다 먹었어요.', '2026-08-23 14:00:00', 20, NULL, NULL),
    (3, 'DEMO-REVIEW-D03', 'member05@cakeshop.local', '딸기 생크림 케이크 1호', 5, 5, 5, 5,
     '생일 케이크로 샀는데 사진보다 실물이 더 예뻐요.', '2026-08-30 17:00:00', 2, NULL, NULL),
    (4, 'DEMO-REVIEW-D04', 'member06@cakeshop.local', '딸기 생크림 케이크 1호', 4, 4, 5, 4,
     '맛은 좋은데 1호라 넷이 먹기엔 조금 작았어요. 다음엔 2호로 살게요.', '2026-09-06 13:00:00', 26, NULL, NULL),
    (5, 'DEMO-REVIEW-D05', 'member07@cakeshop.local', '딸기 생크림 케이크 1호', 5, 5, 5, 5,
     '딸기가 정말 신선해요. 픽업 시간에 딱 맞춰 준비해 주셨어요.', '2026-09-13 16:00:00', 1, NULL, NULL),
    (6, 'DEMO-REVIEW-D06', 'member09@cakeshop.local', '딸기 생크림 케이크 1호', 5, 5, 4, 5,
     '아이가 딸기만 골라 먹을 만큼 딸기가 달았어요.', '2026-09-27 15:00:00', 5, NULL, NULL),
    (7, 'DEMO-REVIEW-D07', 'member10@cakeshop.local', '딸기 생크림 케이크 1호', 4, 4, 4, 5,
     '무난하게 맛있어요. 크림이 조금 더 많았으면 좋겠어요.', '2026-10-11 12:00:00', 30, NULL, NULL),
    (8, 'DEMO-REVIEW-D08', 'member12@cakeshop.local', '딸기 생크림 케이크 1호', 5, 5, 5, 5,
     '두 번째 구매예요. 이번에도 실패 없이 맛있었습니다.', '2026-10-25 14:00:00', 4, NULL, NULL),

    -- 초코 가나슈 케이크 1호 다크초코: 6건, 평균 4.50
    (9, 'DEMO-REVIEW-C01', 'member04@cakeshop.local', '초코 가나슈 케이크 1호 다크초코', 5, 5, 4, 5,
     '다크초코가 진한데 쓰지 않아요. 초코 좋아하면 만족할 맛이에요.', '2026-08-17 18:00:00', 2, NULL, NULL),
    (10, 'DEMO-REVIEW-C02', 'member02@cakeshop.local', '초코 가나슈 케이크 1호 다크초코', 4, 4, 4, 5,
     '가나슈가 꾸덕하고 맛있어요. 단 걸 싫어하면 조금 달 수 있어요.', '2026-08-24 13:00:00', 22, NULL, NULL),
    (11, 'DEMO-REVIEW-C03', 'member08@cakeshop.local', '초코 가나슈 케이크 1호 다크초코', 5, 5, 5, 4,
     '커피랑 같이 먹으니 딱 좋았어요. 겉면 광택이 예뻐요.', '2026-09-07 19:00:00', 3, NULL, NULL),
    (12, 'DEMO-REVIEW-C04', 'member10@cakeshop.local', '초코 가나슈 케이크 1호 다크초코', 4, 5, 3, 4,
     '맛은 최고인데 들고 오다 옆면이 조금 눌렸어요. 상자가 더 단단하면 좋겠어요.', '2026-09-21 11:00:00', 48, NULL, NULL),
    (13, 'DEMO-REVIEW-C05', 'member11@cakeshop.local', '초코 가나슈 케이크 1호 다크초코', 5, 5, 5, 5,
     '남자친구 생일에 샀는데 초코 덕후인 남자친구가 극찬했어요.', '2026-10-05 15:00:00', 6, NULL, NULL),
    (14, 'DEMO-REVIEW-C06', 'member04@cakeshop.local', '초코 가나슈 케이크 1호 다크초코', 4, 4, 4, 4,
     '두 번째 구매예요. 처음보다 살짝 덜 달게 느껴졌지만 여전히 맛있어요.', '2026-10-19 17:00:00', 8, NULL, NULL),

    -- 레몬 바스크 치즈케이크: 5건, 평균 4.60
    (15, 'DEMO-REVIEW-B01', 'member09@cakeshop.local', '레몬 바스크 치즈케이크', 5, 5, 4, 5,
     '겉은 진하게 구워졌는데 속은 꾸덕해서 딱 제 취향이에요.', '2026-09-01 14:00:00', 4, NULL, NULL),
    (16, 'DEMO-REVIEW-B02', 'member03@cakeshop.local', '레몬 바스크 치즈케이크', 5, 5, 5, 4,
     '레몬 향이 은은해서 치즈케이크인데도 무겁지 않아요.', '2026-09-14 16:00:00', 20, NULL, NULL),
    (17, 'DEMO-REVIEW-B03', 'member06@cakeshop.local', '레몬 바스크 치즈케이크', 4, 4, 4, 5,
     '하루 냉장고에 두었다 먹으니 더 맛있었어요.', '2026-09-28 13:00:00', 2, NULL, NULL),
    (18, 'DEMO-REVIEW-B04', 'member12@cakeshop.local', '레몬 바스크 치즈케이크', 5, 5, 4, 5,
     '카페에서 먹던 바스크보다 훨씬 진해요. 또 살게요.', '2026-10-12 15:00:00', 6, NULL, NULL),
    (19, 'DEMO-REVIEW-B05', 'member01@cakeshop.local', '레몬 바스크 치즈케이크', 4, 4, 3, 4,
     '맛은 좋은데 윗면이 생각보다 많이 그을려 보여서 선물용으로는 아쉬웠어요.', '2026-10-26 12:00:00', 28, NULL, NULL),

    -- 레터링 생크림 케이크(주문 제작): 5건, 평균 4.40
    (20, 'DEMO-REVIEW-L01', 'member05@cakeshop.local', '레터링 생크림 케이크', 5, 5, 5, 5,
     '요청한 문구와 색을 그대로 올려 주셔서 부모님 결혼기념일이 더 특별했어요.', '2026-08-21 15:00:00', 5,
     '레터링 문구: 결혼 30주년 축하해요', '글씨는 진한 갈색으로 부탁드려요.'),
    (21, 'DEMO-REVIEW-L02', 'member11@cakeshop.local', '레터링 생크림 케이크', 4, 4, 5, 4,
     '레터링이 정말 깔끔해요. 문의 답변이 하루 늦어서 조금 걱정했어요.', '2026-09-17 17:00:00', 3,
     '레터링 문구: 1000일 축하해', '하트 장식을 작게 넣어 주세요.'),
    (22, 'DEMO-REVIEW-L03', 'member07@cakeshop.local', '레터링 생크림 케이크', 5, 5, 5, 5,
     '친구 깜짝 파티용이었는데 다들 사진부터 찍었어요.', '2026-10-02 14:00:00', 24,
     '레터링 문구: 지은아 생일 축하해', '글씨는 분홍색으로 부탁드려요.'),
    (23, 'DEMO-REVIEW-L04', 'member02@cakeshop.local', '레터링 생크림 케이크', 4, 4, 4, 5,
     '글씨가 반듯하고 맛도 좋아요. 크기에 비해 가격은 조금 있는 편이에요.', '2026-10-16 18:00:00', 6,
     '레터링 문구: 승진 축하합니다', '회사에서 나눠 먹을 거라 깔끔하게 부탁드려요.'),
    (24, 'DEMO-REVIEW-L05', 'member08@cakeshop.local', '레터링 생크림 케이크', 4, 5, 4, 4,
     '맛은 깔끔한 생크림 케이크예요. 문구 위치가 살짝 아래로 치우쳤어요.', '2026-10-30 15:00:00', 2,
     '레터링 문구: 아빠 환갑 축하드려요', '문구는 가운데에 맞춰 주세요.'),

    -- 마스카포네 티라미수 케이크: 4건, 평균 4.25
    (25, 'DEMO-REVIEW-T01', 'member02@cakeshop.local', '마스카포네 티라미수 케이크', 5, 5, 4, 5,
     '마스카포네 크림이 진하고 커피 향이 은은해요.', '2026-08-31 20:00:00', 2, NULL, NULL),
    (26, 'DEMO-REVIEW-T02', 'member08@cakeshop.local', '마스카포네 티라미수 케이크', 4, 4, 4, 4,
     '어른 입맛이에요. 아이들은 커피 맛 때문에 조금 남겼어요.', '2026-09-20 13:00:00', 24, NULL, NULL),
    (27, 'DEMO-REVIEW-T03', 'member10@cakeshop.local', '마스카포네 티라미수 케이크', 4, 4, 4, 5,
     '코코아 가루가 날려서 자를 때 조심해야 해요. 맛은 좋아요.', '2026-10-04 15:00:00', 5, NULL, NULL),
    (28, 'DEMO-REVIEW-T04', 'member12@cakeshop.local', '마스카포네 티라미수 케이크', 4, 5, 4, 4,
     '티라미수 좋아하면 추천해요. 크림이 생각보다 무겁지 않아요.', '2026-10-18 16:00:00', 7, NULL, NULL),

    -- 말차 팥 케이크: 3건, 평균 4.67
    (29, 'DEMO-REVIEW-M01', 'member08@cakeshop.local', '말차 팥 케이크', 5, 5, 5, 5,
     '말차가 쌉싸름하고 팥이 달지 않아서 어른들 선물로 좋았어요.', '2026-08-28 15:00:00', 3, NULL, NULL),
    (30, 'DEMO-REVIEW-M02', 'member01@cakeshop.local', '말차 팥 케이크', 4, 4, 4, 4,
     '말차 맛은 진한데 팥이 조금 적게 느껴졌어요.', '2026-09-19 14:00:00', 20, NULL, NULL),
    (31, 'DEMO-REVIEW-M03', 'member09@cakeshop.local', '말차 팥 케이크', 5, 5, 4, 5,
     '말차 디저트 좋아하는 분이면 꼭 드셔 보세요.', '2026-10-14 13:00:00', 4, NULL, NULL),

    -- 레드벨벳 크림치즈 케이크: 3건, 평균 4.00
    (32, 'DEMO-REVIEW-R01', 'member03@cakeshop.local', '레드벨벳 크림치즈 케이크', 4, 4, 5, 4,
     '단면 색이 예뻐서 사진이 잘 나와요.', '2026-09-03 17:00:00', 6, NULL, NULL),
    (33, 'DEMO-REVIEW-R02', 'member11@cakeshop.local', '레드벨벳 크림치즈 케이크', 3, 3, 4, 4,
     '크림치즈가 생각보다 달아서 저한테는 조금 무거웠어요.', '2026-09-30 15:00:00', 26, NULL, NULL),
    (34, 'DEMO-REVIEW-R03', 'member06@cakeshop.local', '레드벨벳 크림치즈 케이크', 5, 5, 5, 5,
     '크림치즈 프로스팅이 진해서 정말 맛있어요.', '2026-10-23 14:00:00', 2, NULL, NULL),

    -- 애플망고 요거트 케이크: 2건, 평균 4.50
    (35, 'DEMO-REVIEW-A01', 'member07@cakeshop.local', '애플망고 요거트 케이크', 5, 5, 5, 5,
     '망고가 듬뿍 올라가 있고 요거트 크림이 상큼해요.', '2026-08-25 16:00:00', 3, NULL, NULL),
    (36, 'DEMO-REVIEW-A02', 'member05@cakeshop.local', '애플망고 요거트 케이크', 4, 4, 5, 4,
     '여름에 먹기 좋은 맛이에요. 덜 익은 망고 조각이 몇 개 있었어요.', '2026-09-24 15:00:00', 20, NULL, NULL),

    -- 후기 1건씩
    (37, 'DEMO-REVIEW-E01', 'member04@cakeshop.local', '얼그레이 오렌지 케이크', 4, 4, 4, 5,
     '얼그레이 향이 은은하고 오렌지 필이 잘 어울려요.', '2026-09-10 15:00:00', 4, NULL, NULL),
    (38, 'DEMO-REVIEW-W01', 'member10@cakeshop.local', '호두 당근 케이크', 3, 4, 3, 4,
     '맛은 괜찮은데 호두가 조금 눅눅했어요.', '2026-10-07 12:00:00', 30, NULL, NULL),
    (39, 'DEMO-REVIEW-N01', 'member11@cakeshop.local', '몽블랑 밤 케이크', 5, 5, 5, 5,
     '밤 크림이 진하고 부드러워요. 가을에 딱이에요.', '2026-10-28 16:00:00', 3, NULL, NULL),
    (40, 'DEMO-REVIEW-S01', 'member09@cakeshop.local', '미니 도시락 케이크', 2, 3, 2, 4,
     '생각보다 크기가 많이 작고 크림이 한쪽으로 쏠려 있었어요.', '2026-10-09 13:00:00', 5, NULL, NULL);

-- 관리자 계정이 없으면 표본을 비워 아래 모든 단계가 아무것도 넣지 않게 한다.
DELETE FROM `_demo_review_samples`
WHERE NOT EXISTS (
    SELECT 1
    FROM `members` admin
    WHERE admin.`email` = 'admin@cakeshop.local'
      AND admin.`role` = 'ADMIN'
);

-- seed-rds-demo.sql 과 같은 기준으로 상품을 하나씩 고른다.
CREATE TEMPORARY TABLE `_demo_review_products` (
    `product_name` VARCHAR(100) NOT NULL PRIMARY KEY,
    `product_id`   BIGINT       NOT NULL
);

INSERT INTO `_demo_review_products` (`product_name`, `product_id`)
SELECT p.`name`, MIN(p.`id`)
FROM `products` p
INNER JOIN `categories` c ON c.`id` = p.`category_id`
WHERE c.`code` = 'CAKE'
  AND p.`name` IN (SELECT DISTINCT `product_name` FROM `_demo_review_samples`)
GROUP BY p.`name`;

START TRANSACTION;

-- 1. 회원
INSERT INTO `members`
    (`email`, `password`, `name`, `nickname`, `phone`, `role`, `status`,
     `birth_date`, `created_at`, `updated_at`)
SELECT
    m.`email`,
    admin.`password`,
    m.`name`,
    m.`nickname`,
    m.`phone`,
    'USER',
    'ACTIVE',
    m.`birth_date`,
    m.`created_at`,
    m.`created_at`
FROM `_demo_review_members` m
INNER JOIN `members` admin
        ON admin.`email` = 'admin@cakeshop.local'
       AND admin.`role` = 'ADMIN'
WHERE NOT EXISTS (
    SELECT 1 FROM `members` existing WHERE existing.`email` = m.`email`
);

-- 2. 주문 (픽업 완료)
INSERT INTO `orders` (
    `order_number`, `member_id`, `request_key`, `order_type`,
    `orderer_name`, `orderer_phone`, `pickup_name`, `pickup_phone`,
    `original_amount`, `discount_amount`, `final_amount`, `status`,
    `pickup_at`, `payment_expires_at`, `request_message`,
    `under_review_at`, `approved_at`, `ready_at`, `picked_up_at`,
    `approved_by`, `picked_up_by`, `created_at`, `updated_at`
)
SELECT
    sample.`order_number`,
    customer.`id`,
    CONCAT('00000000-0000-0000-0002-', LPAD(sample.`seq`, 12, '0')),
    sample.`product_type`,
    customer.`name`,
    customer.`phone`,
    customer.`name`,
    customer.`phone`,
    sample.`base_price`,
    0,
    sample.`base_price`,
    'PICKED_UP',
    sample.`picked_up_at` - INTERVAL 1 HOUR,
    sample.`ordered_at` + INTERVAL 10 MINUTE,
    sample.`request_message`,
    CASE WHEN sample.`product_type` = 'CUSTOM' THEN sample.`ordered_at` + INTERVAL 5 MINUTE END,
    CASE WHEN sample.`product_type` = 'CUSTOM' THEN sample.`ordered_at` + INTERVAL 1 HOUR END,
    sample.`picked_up_at` - INTERVAL 90 MINUTE,
    sample.`picked_up_at`,
    CASE WHEN sample.`product_type` = 'CUSTOM' THEN admin.`id` END,
    admin.`id`,
    sample.`ordered_at`,
    sample.`picked_up_at`
FROM (
    SELECT
        s.`seq`,
        s.`order_number`,
        s.`member_email`,
        s.`picked_up_at`,
        s.`request_message`,
        p.`product_type`,
        p.`base_price`,
        s.`picked_up_at`
            - INTERVAL CASE WHEN p.`product_type` = 'CUSTOM' THEN 7 ELSE 3 END DAY
            AS `ordered_at`
    FROM `_demo_review_samples` s
    INNER JOIN `_demo_review_products` seed_product
            ON seed_product.`product_name` = s.`product_name`
    INNER JOIN `products` p
            ON p.`id` = seed_product.`product_id`
) sample
INNER JOIN `members` customer
        ON customer.`email` = sample.`member_email`
INNER JOIN `members` admin
        ON admin.`email` = 'admin@cakeshop.local'
       AND admin.`role` = 'ADMIN'
WHERE NOT EXISTS (
    SELECT 1 FROM `orders` existing WHERE existing.`order_number` = sample.`order_number`
);

-- 3. 주문 상품
INSERT INTO `order_items` (
    `order_id`, `product_id`, `product_name`, `product_type`, `quantity`,
    `base_price`, `option_amount`, `total_amount`, `requirements`,
    `preparation_days`, `stock_deducted_at`, `stock_restored_at`
)
SELECT
    o.`id`,
    p.`id`,
    p.`name`,
    p.`product_type`,
    1,
    p.`base_price`,
    0,
    p.`base_price`,
    s.`requirements`,
    p.`preparation_days`,
    CASE WHEN p.`product_type` = 'GENERAL' THEN o.`created_at` + INTERVAL 5 MINUTE END,
    NULL
FROM `orders` o
INNER JOIN `_demo_review_samples` s
        ON s.`order_number` = o.`order_number`
INNER JOIN `_demo_review_products` seed_product
        ON seed_product.`product_name` = s.`product_name`
INNER JOIN `products` p
        ON p.`id` = seed_product.`product_id`
WHERE NOT EXISTS (
    SELECT 1 FROM `order_items` existing WHERE existing.`order_id` = o.`id`
);

-- 4. 주문 제작 옵션 스냅샷 (케이크 크기 1호 · 크림 색상 화이트, 추가 금액 0원)
INSERT INTO `order_item_options` (
    `order_item_id`, `product_option_id`, `option_group_name`, `option_name`, `additional_price`
)
SELECT
    oi.`id`,
    po.`id`,
    pog.`name`,
    po.`name`,
    po.`additional_price`
FROM `orders` o
INNER JOIN `_demo_review_samples` s
        ON s.`order_number` = o.`order_number`
INNER JOIN `order_items` oi
        ON oi.`order_id` = o.`id`
       AND oi.`product_type` = 'CUSTOM'
INNER JOIN (
    SELECT '케이크 크기' AS `group_name`, '1호' AS `option_name`
    UNION ALL
    SELECT '크림 색상', '화이트'
) chosen
INNER JOIN `product_option_groups` pog
        ON pog.`product_id` = oi.`product_id`
       AND pog.`name` = chosen.`group_name`
       AND pog.`id` = (
           SELECT MIN(seed_group.`id`)
           FROM `product_option_groups` seed_group
           WHERE seed_group.`product_id` = oi.`product_id`
             AND seed_group.`name` = chosen.`group_name`
       )
INNER JOIN `product_options` po
        ON po.`option_group_id` = pog.`id`
       AND po.`name` = chosen.`option_name`
       AND po.`id` = (
           SELECT MIN(seed_option.`id`)
           FROM `product_options` seed_option
           WHERE seed_option.`option_group_id` = pog.`id`
             AND seed_option.`name` = chosen.`option_name`
       )
WHERE NOT EXISTS (
    SELECT 1 FROM `order_item_options` existing WHERE existing.`order_item_id` = oi.`id`
);

-- 5. 결제 완료 이력 (돈워리 도입 전 주문이라 Toss 결제로 남긴다)
INSERT INTO `payments` (
    `order_id`, `provider`, `toss_order_id`, `payment_key`, `idempotency_key`,
    `method`, `amount`, `status`, `requested_at`, `approved_at`, `created_at`, `updated_at`
)
SELECT
    o.`id`,
    'TOSS',
    CONCAT('TOSS-', o.`order_number`),
    CONCAT('PAYMENT-KEY-', o.`order_number`),
    CONCAT('PAYMENT-IDEMPOTENCY-', o.`order_number`),
    'CARD',
    o.`final_amount`,
    'DONE',
    o.`created_at`,
    o.`created_at` + INTERVAL 5 MINUTE,
    o.`created_at`,
    o.`created_at` + INTERVAL 5 MINUTE
FROM `orders` o
INNER JOIN `_demo_review_samples` s
        ON s.`order_number` = o.`order_number`
WHERE NOT EXISTS (
    SELECT 1 FROM `payments` existing WHERE existing.`order_id` = o.`id`
);

-- 6. 후기 (공개)
INSERT INTO `reviews` (
    `order_item_id`, `product_id`, `member_id`,
    `overall_rating`, `taste_rating`, `design_rating`, `service_rating`,
    `content`, `status`, `created_at`, `updated_at`
)
SELECT
    oi.`id`,
    oi.`product_id`,
    o.`member_id`,
    s.`overall_rating`,
    s.`taste_rating`,
    s.`design_rating`,
    s.`service_rating`,
    s.`content`,
    'PUBLISHED',
    s.`picked_up_at` + INTERVAL s.`review_after_hours` HOUR,
    s.`picked_up_at` + INTERVAL s.`review_after_hours` HOUR
FROM `orders` o
INNER JOIN `_demo_review_samples` s
        ON s.`order_number` = o.`order_number`
INNER JOIN `order_items` oi
        ON oi.`order_id` = o.`id`
WHERE NOT EXISTS (
    SELECT 1 FROM `reviews` existing WHERE existing.`order_item_id` = oi.`id`
);

-- 7. 후기를 받은 상품의 평균 별점·후기 수를 공개 후기 기준으로 다시 맞춘다.
--    다른 사용자가 쓴 후기가 있으면 그 후기도 함께 계산된다.
UPDATE `products` p
INNER JOIN `_demo_review_products` seed_product
        ON seed_product.`product_id` = p.`id`
LEFT JOIN (
    SELECT
        r.`product_id`,
        AVG(r.`overall_rating`) AS `average_rating`,
        COUNT(*) AS `review_count`
    FROM `reviews` r
    WHERE r.`status` = 'PUBLISHED'
    GROUP BY r.`product_id`
) review_aggregate
        ON review_aggregate.`product_id` = p.`id`
SET p.`average_rating` = COALESCE(review_aggregate.`average_rating`, 0.00),
    p.`review_count` = COALESCE(review_aggregate.`review_count`, 0),
    p.`updated_at` = p.`updated_at`;

COMMIT;

DROP TEMPORARY TABLE `_demo_review_members`;
DROP TEMPORARY TABLE `_demo_review_samples`;
DROP TEMPORARY TABLE `_demo_review_products`;
