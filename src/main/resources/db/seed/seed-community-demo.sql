-- 시연용 커뮤니티 게시글 로컬 seed
--
-- seed-review-demo.sql 의 시연 회원 12명이 쓴 게시글 24건(질문·후기·자유 8건씩)과 댓글·답글,
-- 좋아요를 넣는다. 후기 글은 그 회원이 실제로 픽업하고 별점 후기를 남긴 케이크 이야기다.
-- 질문 글에는 관리자 답변 댓글이 달린다.
--
-- 기존 데이터를 지우지 않는다. 같은 작성자의 같은 제목 글, 같은 글에 같은 작성자가 쓴 같은 댓글,
-- 이미 누른 좋아요는 다시 넣지 않는다. 그래서 여러 번 실행해도 결과가 같다.
-- Flyway 관리 대상이 아니다. migration 을 모두 적용한 뒤 MariaDB 클라이언트로 수동 실행한다.
--
-- 실행 순서: seed-local.sql → (선택) seed-community.sql → seed-review-demo.sql → 이 파일
--   seed-community.sql 은 게시글을 모두 지우므로 이 파일보다 먼저 실행한다.
--   시연 회원(member01~12@cakeshop.local)이 없으면 그 회원의 글·댓글·좋아요는 넣지 않는다.
--   카테고리(QNA·REVIEW·FREE)가 없으면 글을 넣지 않는다.
--
-- 조회수(posts.view_count)는 숫자로 넣는다. 좋아요 수는 넣은 좋아요 행으로 다시 센다.
--
-- 인기글: 실행한 날의 "어제"(한국 시간)에 시연 글 조회 이력을 만들고, 배치와 같은 집계식으로
--   그날 인기글을 확정한다. 화면은 확정된 날짜 중 가장 최근 것만 읽는데, 매일 00:05 배치가
--   활동이 없는 날도 0건으로 확정하므로 다음 날 활동이 없으면 사이드바가 다시 비어 보인다.
--   그때는 이 파일을 다시 실행하면 새 "어제"로 채워진다.
-- 이 파일은 Spring ScriptUtils 로도 실행하므로 문자열 안에 세미콜론을 쓰지 않는다.

SET time_zone = '+09:00';
SET @activity_day := CURDATE() - INTERVAL 1 DAY;

DROP TEMPORARY TABLE IF EXISTS `_demo_community_posts`;
DROP TEMPORARY TABLE IF EXISTS `_demo_community_comments`;
DROP TEMPORARY TABLE IF EXISTS `_demo_numbers`;

-- 어제 조회 이력을 글마다 여러 줄 만들 때 쓰는 1~20
CREATE TEMPORARY TABLE `_demo_numbers` (`k` TINYINT NOT NULL PRIMARY KEY);
INSERT INTO `_demo_numbers` (`k`) VALUES
    (1), (2), (3), (4), (5), (6), (7), (8), (9), (10),
    (11), (12), (13), (14), (15), (16), (17), (18), (19), (20);

CREATE TEMPORARY TABLE `_demo_community_posts` (
    `post_key`      VARCHAR(10)  NOT NULL PRIMARY KEY,
    `author_email`  VARCHAR(255) NOT NULL,
    `category_code` VARCHAR(30)  NOT NULL,
    `title`         VARCHAR(200) NOT NULL,
    `content`       TEXT         NOT NULL,
    `created_at`    DATETIME(6)  NOT NULL,
    `view_count`    BIGINT       NOT NULL,
    `like_target`   TINYINT      NOT NULL
);

INSERT INTO `_demo_community_posts`
    (`post_key`, `author_email`, `category_code`, `title`, `content`, `created_at`, `view_count`, `like_target`)
VALUES
    -- 후기: 작성자가 그 케이크를 픽업한 다음 날 쓴 글
    ('P01', 'member05@cakeshop.local', 'REVIEW', '부모님 결혼 30주년 레터링 케이크 후기',
     '부모님 결혼 30주년이라 레터링 케이크를 주문했어요.\n\n글씨 색을 진한 갈색으로 부탁드렸는데 요청한 그대로 나왔고, 생크림이 느끼하지 않아서 부모님이 한 조각씩 더 드셨어요.\n\n주문 제작은 일주일 전에는 넣어야 하니 미리 준비하시는 게 좋아요.',
     '2026-08-22 20:00:00', 184, 9),
    ('P02', 'member04@cakeshop.local', 'REVIEW', '다크초코 가나슈 1호 솔직 후기',
     '초코 케이크는 달기만 한 곳이 많아서 걱정했는데 여기는 다크초코라 쌉싸름한 맛이 먼저 와요.\n\n가나슈가 두꺼워서 냉장고에서 꺼내고 20분쯤 두었다가 먹으니 제일 맛있었어요.',
     '2026-08-18 21:00:00', 231, 11),
    ('P03', 'member09@cakeshop.local', 'REVIEW', '바스크 치즈케이크 꾸덕함 단계 공유',
     '꾸덕한 치즈케이크 좋아하시는 분들 참고하세요.\n\n픽업 당일 바로 먹으면 부드러운 편이고, 하루 냉장 보관하면 훨씬 꾸덕해져요. 레몬 향은 은은한 정도라 레몬을 안 좋아하셔도 괜찮아요.',
     '2026-09-02 19:00:00', 162, 8),
    ('P04', 'member07@cakeshop.local', 'REVIEW', '애플망고 요거트 케이크 사진 공유해요',
     '망고가 생각보다 훨씬 많이 올라가 있었어요. 요거트 크림이 상큼해서 더운 날 먹기 딱 좋았습니다.\n\n친구들이 다 어디서 샀냐고 물어봐서 여기 알려 줬어요.',
     '2026-08-26 18:00:00', 97, 5),
    ('P05', 'member12@cakeshop.local', 'REVIEW', '딸기 생크림 1호 재구매 후기',
     '첫 구매 때 너무 맛있어서 이번에 두 번째로 샀어요.\n\n딸기가 여전히 크고 달았고, 픽업 시간에 맞춰 미리 준비돼 있어서 바로 받아 왔어요. 1호는 성인 셋이 먹기에 딱 좋은 크기예요.',
     '2026-10-26 20:00:00', 143, 7),
    ('P06', 'member08@cakeshop.local', 'REVIEW', '말차 팥 케이크, 어른 선물로 추천해요',
     '시부모님 댁에 가져갔는데 단 걸 별로 안 좋아하시는 분들이 다 드셨어요.\n\n말차가 진하고 팥이 달지 않은 게 포인트예요. 따뜻한 녹차랑 같이 드시면 더 좋아요.',
     '2026-08-29 21:00:00', 88, 4),
    ('P07', 'member11@cakeshop.local', 'REVIEW', '1000일 레터링 케이크 받고 왔어요',
     '1000일 기념으로 레터링 케이크를 주문했어요. 하트 장식을 작게 넣어 달라고 했는데 정말 귀엽게 해 주셨어요.\n\n상담 답장이 하루 늦어서 조금 조마조마했지만 결과물은 만족이에요.',
     '2026-09-18 22:00:00', 121, 6),
    ('P08', 'member06@cakeshop.local', 'REVIEW', '레드벨벳 단면 사진 맛집',
     '레드벨벳 크림치즈 케이크를 샀는데 자르는 순간 단면이 너무 예뻐서 사진부터 찍었어요.\n\n크림치즈 프로스팅이 진해서 커피랑 먹으니 딱이었어요.',
     '2026-10-24 19:00:00', 76, 3),

    -- 질문: 관리자 답변이 달린다
    ('P09', 'member06@cakeshop.local', 'QNA', '넷이 먹으려면 몇 호가 좋을까요?',
     '지난번에 1호를 샀는데 넷이 먹기엔 조금 부족했어요. 2호는 몇 명까지 괜찮을까요?',
     '2026-09-08 10:00:00', 205, 6),
    ('P10', 'member10@cakeshop.local', 'QNA', '픽업 시간 변경 가능한가요?',
     '오후 3시 픽업으로 주문했는데 일이 생겨서 6시쯤 가야 할 것 같아요. 주문 후에 픽업 시간을 바꿀 수 있나요?',
     '2026-09-15 09:00:00', 134, 3),
    ('P11', 'member03@cakeshop.local', 'QNA', '케이크 들고 1시간 이동해도 괜찮을까요?',
     '차로 1시간 정도 가야 하는데 생크림 케이크가 녹지 않을지 걱정돼요. 보냉 포장을 따로 해 주시나요?',
     '2026-08-12 14:00:00', 167, 4),
    ('P12', 'member02@cakeshop.local', 'QNA', '레터링 문구 길이 제한이 있나요?',
     '회사 행사용으로 주문하려는데 문구가 좀 길어요. 몇 글자까지 넣을 수 있는지 궁금합니다.',
     '2026-10-10 11:00:00', 92, 2),
    ('P13', 'member09@cakeshop.local', 'QNA', '미니 도시락 케이크 크기 질문',
     '도시락 케이크는 혼자 먹기에 어느 정도 크기인가요? 사진으로는 감이 잘 안 와요.',
     '2026-10-04 16:00:00', 71, 1),
    ('P14', 'member01@cakeshop.local', 'QNA', '생일 초는 따로 챙겨 주시나요?',
     '아이 생일 케이크를 주문했는데 초랑 칼은 같이 주시는지 궁금해요.',
     '2026-09-22 13:00:00', 58, 2),
    ('P15', 'member12@cakeshop.local', 'QNA', '크리스마스 케이크 예약은 언제부터인가요?',
     '작년에 늦게 알아봐서 못 샀어요. 올해는 언제부터 예약을 받는지 알 수 있을까요?',
     '2026-11-02 10:00:00', 112, 5),
    ('P16', 'member07@cakeshop.local', 'QNA', '결제 후에 주문 확인 중이라고 떠요',
     '결제는 끝났는데 주문 상태가 주문 확인 중으로 나와요. 기다리면 되는 건가요?',
     '2026-11-04 15:00:00', 49, 2),

    -- 자유
    ('P17', 'member01@cakeshop.local', 'FREE', '남은 케이크 다들 어떻게 보관하세요?',
     '다 못 먹고 남은 케이크는 랩으로 싸서 냉장 보관하는데 다음 날이 되면 크림이 좀 굳더라고요. 다들 어떻게 보관하시는지 궁금해요.',
     '2026-08-20 22:00:00', 119, 5),
    ('P18', 'member10@cakeshop.local', 'FREE', '딸 생일 케이크 들고 가는 길',
     '퇴근하고 케이크 픽업해서 집에 가는 길인데 괜히 설레네요. 딸이 딸기 케이크를 제일 좋아해서 이번에도 딸기로 했어요.',
     '2026-10-11 13:00:00', 64, 8),
    ('P19', 'member08@cakeshop.local', 'FREE', '몽블랑 밤 케이크 드셔 보신 분?',
     '가을이라 몽블랑이 계속 눈에 밟히는데 드셔 보신 분 계신가요? 밤 맛이 진한지 궁금해요.',
     '2026-09-25 20:00:00', 83, 3),
    ('P20', 'member04@cakeshop.local', 'FREE', '초코 케이크 좋아하는 분들 모여요',
     '다크초코 가나슈를 두 번 먹어 본 사람으로서 초코 좋아하는 분들 추천 메뉴 공유해요. 저는 다크초코 1호가 최고였어요. 화이트도 궁금하네요.',
     '2026-10-20 22:00:00', 101, 4),
    ('P21', 'member02@cakeshop.local', 'FREE', '집에서 생크림 케이크 만들어 봤는데',
     '주말마다 베이킹을 하는데 집에서 생크림 아이싱을 해 보니 여기 케이크가 얼마나 깔끔한지 알겠더라고요. 아이싱 잘하는 팁 있으신 분?',
     '2026-09-01 08:00:00', 77, 3),
    ('P22', 'member03@cakeshop.local', 'FREE', '티라미수랑 얼그레이 중에 고민이에요',
     '다음에 티라미수랑 얼그레이 오렌지 중에 하나를 사 보려고요. 둘 다 드셔 보신 분들 어떤 게 더 좋았나요?',
     '2026-10-30 18:00:00', 95, 4),
    ('P23', 'member05@cakeshop.local', 'FREE', '기념일마다 케이크 사는 습관',
     '결혼기념일, 생일, 부모님 기념일까지 1년에 케이크를 열 번은 사는 것 같아요. 여기서 주문 제작을 해 보고 나서는 기념일 케이크는 여기로 정착했어요.',
     '2026-10-18 21:00:00', 68, 6),
    ('P24', 'member11@cakeshop.local', 'FREE', '케이크 픽업하고 산책하기 좋은 날',
     '케이크 픽업하고 근처 공원을 한 바퀴 도는 게 요즘 소소한 행복이에요. 날씨 좋을 때 추천해요.',
     '2026-11-03 20:00:00', 41, 2);

-- 답글은 parent_author_email·parent_content 로 부모 댓글을 찾는다.
-- MariaDB 는 한 쿼리에서 같은 임시 테이블을 두 번 열 수 없어서, 글 제목·작성자를 아래 UPDATE 로
-- 이 표에 미리 옮겨 둔다.
CREATE TEMPORARY TABLE `_demo_community_comments` (
    `seq`                 SMALLINT     NOT NULL PRIMARY KEY,
    `post_key`            VARCHAR(10)  NOT NULL,
    `post_title`          VARCHAR(200) NULL,
    `post_author_email`   VARCHAR(255) NULL,
    `author_email`        VARCHAR(255) NOT NULL,
    `content`             TEXT         NOT NULL,
    `created_at`          DATETIME(6)  NOT NULL,
    `parent_author_email` VARCHAR(255) NULL,
    `parent_content`      TEXT         NULL
);

INSERT INTO `_demo_community_comments`
    (`seq`, `post_key`, `author_email`, `content`, `created_at`, `parent_author_email`, `parent_content`)
VALUES
    (1,  'P01', 'member07@cakeshop.local', '글씨 색 지정도 되는군요. 저도 기념일에 주문해 봐야겠어요.', '2026-08-23 10:00:00', NULL, NULL),
    (2,  'P01', 'member05@cakeshop.local', '네 상담할 때 말씀드리면 맞춰 주세요!', '2026-08-23 12:00:00',
     'member07@cakeshop.local', '글씨 색 지정도 되는군요. 저도 기념일에 주문해 봐야겠어요.'),
    (3,  'P02', 'member06@cakeshop.local', '20분 두었다 먹는 팁 좋네요.', '2026-08-19 09:00:00', NULL, NULL),
    (4,  'P02', 'member02@cakeshop.local', '다크초코인데도 아이들이 잘 먹을까요?', '2026-08-25 21:00:00', NULL, NULL),
    (5,  'P02', 'member04@cakeshop.local', '아이들 입맛에는 조금 쓸 수 있어요. 화이트를 추천해요.', '2026-08-25 22:00:00',
     'member02@cakeshop.local', '다크초코인데도 아이들이 잘 먹을까요?'),
    (6,  'P03', 'member01@cakeshop.local', '하루 냉장 보관 꿀팁 감사해요.', '2026-09-03 12:00:00', NULL, NULL),
    (7,  'P03', 'member12@cakeshop.local', '저도 꾸덕파라 다음엔 하루 두고 먹어 볼게요.', '2026-09-21 20:00:00', NULL, NULL),
    (8,  'P03', 'member09@cakeshop.local', '꼭 해 보세요. 식감이 완전 달라져요.', '2026-09-21 21:00:00',
     'member12@cakeshop.local', '저도 꾸덕파라 다음엔 하루 두고 먹어 볼게요.'),
    (9,  'P04', 'member05@cakeshop.local', '망고 정말 많네요. 글만 봐도 맛있어 보여요.', '2026-08-27 09:00:00', NULL, NULL),
    (10, 'P05', 'member06@cakeshop.local', '1호 성인 셋 기준 정보 감사합니다.', '2026-10-27 08:00:00', NULL, NULL),
    (11, 'P05', 'member10@cakeshop.local', '딸기 1호는 실패가 없죠.', '2026-10-27 12:00:00', NULL, NULL),
    (12, 'P06', 'member01@cakeshop.local', '녹차랑 같이 먹는 조합 좋네요.', '2026-08-30 10:00:00', NULL, NULL),
    (13, 'P07', 'member05@cakeshop.local', '하트 장식 귀여워요. 축하드려요!', '2026-09-19 09:00:00', NULL, NULL),
    (14, 'P07', 'member11@cakeshop.local', '감사합니다. 다음 기념일에도 여기서 하려고요.', '2026-09-19 10:00:00',
     'member05@cakeshop.local', '하트 장식 귀여워요. 축하드려요!'),
    (15, 'P08', 'member03@cakeshop.local', '레드벨벳 단면은 진짜 사진 맛집이죠.', '2026-10-25 11:00:00', NULL, NULL),
    (16, 'P09', 'member01@cakeshop.local', '저는 넷이서 2호 먹었는데 딱 맞았어요.', '2026-09-08 12:00:00', NULL, NULL),
    (17, 'P09', 'admin@cakeshop.local', '안녕하세요, 스윗한 케이크입니다. 1호는 2~3인, 2호는 3~4인, 3호는 5~6인 기준으로 준비하고 있습니다. 넷이 드시면 2호를 추천드려요.', '2026-09-08 15:00:00', NULL, NULL),
    (18, 'P09', 'member06@cakeshop.local', '답변 감사합니다. 다음엔 2호로 주문할게요!', '2026-09-08 18:00:00',
     'admin@cakeshop.local', '안녕하세요, 스윗한 케이크입니다. 1호는 2~3인, 2호는 3~4인, 3호는 5~6인 기준으로 준비하고 있습니다. 넷이 드시면 2호를 추천드려요.'),
    (19, 'P10', 'admin@cakeshop.local', '픽업 시간 변경이 필요하시면 1:1 문의에 주문번호와 원하시는 시간을 남겨 주세요. 확인 후 바로 조정해 드리겠습니다.', '2026-09-15 10:30:00', NULL, NULL),
    (20, 'P10', 'member10@cakeshop.local', '문의 남겼어요. 감사합니다.', '2026-09-15 11:00:00',
     'admin@cakeshop.local', '픽업 시간 변경이 필요하시면 1:1 문의에 주문번호와 원하시는 시간을 남겨 주세요. 확인 후 바로 조정해 드리겠습니다.'),
    (21, 'P11', 'admin@cakeshop.local', '보냉 팩을 함께 넣어 드리고 있습니다. 1시간 이내 이동은 괜찮지만 차 안을 시원하게 해 주시고 케이크는 평평한 곳에 두어 주세요.', '2026-08-12 16:00:00', NULL, NULL),
    (22, 'P11', 'member03@cakeshop.local', '자세히 알려 주셔서 감사해요.', '2026-08-12 17:00:00',
     'admin@cakeshop.local', '보냉 팩을 함께 넣어 드리고 있습니다. 1시간 이내 이동은 괜찮지만 차 안을 시원하게 해 주시고 케이크는 평평한 곳에 두어 주세요.'),
    (23, 'P12', 'member11@cakeshop.local', '저는 열 글자 조금 넘게 넣었는데 깔끔하게 들어갔어요.', '2026-10-10 13:00:00', NULL, NULL),
    (24, 'P12', 'admin@cakeshop.local', '1호 기준 15자 안팎이 가장 깔끔하게 들어갑니다. 더 긴 문구는 상담 때 배치를 함께 정해 드려요.', '2026-10-10 15:00:00', NULL, NULL),
    (25, 'P13', 'member04@cakeshop.local', '혼자 먹기엔 딱인데 둘이면 좀 작아요.', '2026-10-04 18:00:00', NULL, NULL),
    (26, 'P13', 'admin@cakeshop.local', '도시락 케이크는 1인용으로 준비하고 있습니다. 두 분 이상이면 1호를 추천드려요.', '2026-10-04 19:00:00', NULL, NULL),
    (27, 'P14', 'admin@cakeshop.local', '초와 칼은 기본으로 함께 드립니다. 숫자 초가 필요하시면 주문 요청사항에 적어 주세요.', '2026-09-22 15:00:00', NULL, NULL),
    (28, 'P14', 'member10@cakeshop.local', '숫자 초 챙겨 주셔서 저도 좋았어요.', '2026-09-22 20:00:00', NULL, NULL),
    (29, 'P15', 'admin@cakeshop.local', '크리스마스·연말 케이크는 11월 1일부터 예약을 받고 있습니다. 자세한 내용은 공지사항을 확인해 주세요.', '2026-11-02 11:00:00', NULL, NULL),
    (30, 'P15', 'member03@cakeshop.local', '저도 예약하려고 했는데 정보 감사해요.', '2026-11-02 13:00:00', NULL, NULL),
    (31, 'P16', 'admin@cakeshop.local', '안전결제 확인 과정이 진행 중일 때 보이는 상태입니다. 확인이 끝나면 자동으로 다음 단계로 넘어가니 조금만 기다려 주세요.', '2026-11-04 16:00:00', NULL, NULL),
    (32, 'P16', 'member07@cakeshop.local', '방금 다음 단계로 넘어갔어요. 감사합니다!', '2026-11-04 17:00:00',
     'admin@cakeshop.local', '안전결제 확인 과정이 진행 중일 때 보이는 상태입니다. 확인이 끝나면 자동으로 다음 단계로 넘어가니 조금만 기다려 주세요.'),
    (33, 'P17', 'member02@cakeshop.local', '밀폐 용기에 넣으면 냉장고 냄새가 덜 배요.', '2026-08-21 09:00:00', NULL, NULL),
    (34, 'P17', 'member06@cakeshop.local', '저는 한 조각씩 랩으로 싸서 둬요.', '2026-08-21 13:00:00', NULL, NULL),
    (35, 'P17', 'member01@cakeshop.local', '밀폐 용기 좋네요. 해 볼게요.', '2026-08-21 20:00:00',
     'member02@cakeshop.local', '밀폐 용기에 넣으면 냉장고 냄새가 덜 배요.'),
    (36, 'P18', 'member05@cakeshop.local', '따님이 좋아했겠어요!', '2026-10-11 18:00:00', NULL, NULL),
    (37, 'P18', 'member10@cakeshop.local', '엄청 좋아했어요. 케이크 앞에서 사진만 열 장 찍었어요.', '2026-10-11 21:00:00',
     'member05@cakeshop.local', '따님이 좋아했겠어요!'),
    (38, 'P19', 'member12@cakeshop.local', '저도 궁금했어요.', '2026-09-26 10:00:00', NULL, NULL),
    (39, 'P19', 'member11@cakeshop.local', '어제 먹어 봤는데 밤 크림이 진하고 부드러워요. 추천해요!', '2026-10-29 12:00:00', NULL, NULL),
    (40, 'P20', 'member02@cakeshop.local', '다크초코 1호 인정합니다. 꾸덕해요.', '2026-10-21 09:00:00', NULL, NULL),
    (41, 'P20', 'member11@cakeshop.local', '저도 남자친구 생일에 다크초코 샀는데 반응 최고였어요.', '2026-10-21 12:00:00', NULL, NULL),
    (42, 'P21', 'member03@cakeshop.local', '돌림판 돌리는 속도랑 스패출러 각도가 중요하더라고요.', '2026-09-01 12:00:00', NULL, NULL),
    (43, 'P21', 'member02@cakeshop.local', '각도 연습해 볼게요. 감사합니다!', '2026-09-01 14:00:00',
     'member03@cakeshop.local', '돌림판 돌리는 속도랑 스패출러 각도가 중요하더라고요.'),
    (44, 'P22', 'member12@cakeshop.local', '티라미수 추천이요. 크림이 생각보다 무겁지 않아요.', '2026-10-31 09:00:00', NULL, NULL),
    (45, 'P22', 'member04@cakeshop.local', '얼그레이 향 좋아하시면 얼그레이요. 오렌지 필이 잘 어울려요.', '2026-10-31 11:00:00', NULL, NULL),
    (46, 'P22', 'member03@cakeshop.local', '티라미수로 마음이 기울어요.', '2026-11-01 10:00:00',
     'member12@cakeshop.local', '티라미수 추천이요. 크림이 생각보다 무겁지 않아요.'),
    (47, 'P23', 'member11@cakeshop.local', '기념일 케이크 정착 공감해요.', '2026-10-19 10:00:00', NULL, NULL),
    (48, 'P24', 'member09@cakeshop.local', '날씨 좋을 때 가 봐야겠어요.', '2026-11-04 09:00:00', NULL, NULL);

UPDATE `_demo_community_comments` c
INNER JOIN `_demo_community_posts` p ON p.`post_key` = c.`post_key`
SET c.`post_title` = p.`title`,
    c.`post_author_email` = p.`author_email`;

START TRANSACTION;

-- 1. 게시글
INSERT INTO `posts` (`member_id`, `category_id`, `title`, `content`, `view_count`, `like_count`,
                     `status`, `created_at`, `updated_at`)
SELECT
    author.`id`,
    category.`id`,
    sample.`title`,
    sample.`content`,
    sample.`view_count`,
    0,
    'PUBLISHED',
    sample.`created_at`,
    sample.`created_at`
FROM `_demo_community_posts` sample
INNER JOIN `members` author
        ON author.`email` = sample.`author_email`
INNER JOIN `post_categories` category
        ON category.`code` = sample.`category_code`
WHERE NOT EXISTS (
    SELECT 1
    FROM `posts` existing
    WHERE existing.`member_id` = author.`id`
      AND existing.`title` = sample.`title`
);

-- 2. 댓글 (부모 댓글 먼저)
INSERT INTO `comments` (`post_id`, `member_id`, `parent_comment_id`, `content`, `status`,
                        `created_at`, `updated_at`)
SELECT
    post.`id`,
    author.`id`,
    NULL,
    sample.`content`,
    'PUBLISHED',
    sample.`created_at`,
    sample.`created_at`
FROM `_demo_community_comments` sample
INNER JOIN `members` post_author
        ON post_author.`email` = sample.`post_author_email`
INNER JOIN `posts` post
        ON post.`member_id` = post_author.`id`
       AND post.`title` = sample.`post_title`
INNER JOIN `members` author
        ON author.`email` = sample.`author_email`
WHERE sample.`parent_author_email` IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM `comments` existing
      WHERE existing.`post_id` = post.`id`
        AND existing.`member_id` = author.`id`
        AND existing.`content` = sample.`content`
  );

-- 3. 답글
INSERT INTO `comments` (`post_id`, `member_id`, `parent_comment_id`, `content`, `status`,
                        `created_at`, `updated_at`)
SELECT
    post.`id`,
    author.`id`,
    parent.`id`,
    sample.`content`,
    'PUBLISHED',
    sample.`created_at`,
    sample.`created_at`
FROM `_demo_community_comments` sample
INNER JOIN `members` post_author
        ON post_author.`email` = sample.`post_author_email`
INNER JOIN `posts` post
        ON post.`member_id` = post_author.`id`
       AND post.`title` = sample.`post_title`
INNER JOIN `members` author
        ON author.`email` = sample.`author_email`
INNER JOIN `members` parent_author
        ON parent_author.`email` = sample.`parent_author_email`
INNER JOIN `comments` parent
        ON parent.`post_id` = post.`id`
       AND parent.`member_id` = parent_author.`id`
       AND parent.`parent_comment_id` IS NULL
       AND parent.`content` = sample.`parent_content`
WHERE sample.`parent_author_email` IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM `comments` existing
      WHERE existing.`post_id` = post.`id`
        AND existing.`member_id` = author.`id`
        AND existing.`content` = sample.`content`
  );

-- 4. 좋아요: 글마다 like_target 명. 작성자와 글보다 늦게 가입한 회원은 빼고,
--    글마다 시작 회원을 돌려 가며 고른다. 좋아요 시각은 글 작성 후 1시간 간격이다.
INSERT INTO `post_likes` (`post_id`, `member_id`, `created_at`)
SELECT
    ranked.`post_id`,
    ranked.`liker_id`,
    ranked.`post_created_at` + INTERVAL ranked.`rn` HOUR
FROM (
    SELECT
        post.`id` AS `post_id`,
        liker.`id` AS `liker_id`,
        sample.`created_at` AS `post_created_at`,
        sample.`like_target`,
        ROW_NUMBER() OVER (
            PARTITION BY post.`id`
            ORDER BY MOD(
                         CAST(SUBSTRING(liker.`email`, 7, 2) AS UNSIGNED)
                         + CAST(SUBSTRING(sample.`post_key`, 2) AS UNSIGNED),
                         12),
                     liker.`id`
        ) AS `rn`
    FROM `_demo_community_posts` sample
    INNER JOIN `members` post_author
            ON post_author.`email` = sample.`author_email`
    INNER JOIN `posts` post
            ON post.`member_id` = post_author.`id`
           AND post.`title` = sample.`title`
    INNER JOIN `members` liker
            ON liker.`email` LIKE 'member__@cakeshop.local'
           AND liker.`id` <> post_author.`id`
           AND liker.`created_at` < sample.`created_at`
) ranked
WHERE ranked.`rn` <= ranked.`like_target`
  AND NOT EXISTS (
      SELECT 1
      FROM `post_likes` existing
      WHERE existing.`post_id` = ranked.`post_id`
        AND existing.`member_id` = ranked.`liker_id`
  );

-- 5. 좋아요 수를 좋아요 행으로 다시 센다.
UPDATE `posts` post
INNER JOIN `members` post_author
        ON post_author.`id` = post.`member_id`
       AND post_author.`email` LIKE 'member__@cakeshop.local'
LEFT JOIN (
    SELECT `post_id`, COUNT(*) AS `like_count`
    FROM `post_likes`
    GROUP BY `post_id`
) likes
        ON likes.`post_id` = post.`id`
SET post.`like_count` = COALESCE(likes.`like_count`, 0),
    post.`updated_at` = post.`updated_at`;

-- 6. 어제 조회 이력: 시연 글마다 조회수의 1/12(최대 20회)만큼 어제 조회가 있었던 것으로 둔다.
--    조회수 숫자는 이 이력까지 포함한 누적값으로 보고 따로 올리지 않는다.
INSERT INTO `post_views` (`post_id`, `viewer_key`, `viewed_on`, `created_at`)
SELECT
    post.`id`,
    CONCAT('demo-seed-viewer-', n.`k`),
    @activity_day,
    TIMESTAMP(@activity_day, '09:00:00') + INTERVAL (n.`k` * 41) MINUTE
FROM `_demo_community_posts` sample
INNER JOIN `members` post_author
        ON post_author.`email` = sample.`author_email`
INNER JOIN `posts` post
        ON post.`member_id` = post_author.`id`
       AND post.`title` = sample.`title`
INNER JOIN `_demo_numbers` n
        ON n.`k` <= LEAST(20, CEIL(sample.`view_count` / 12))
WHERE NOT EXISTS (
    SELECT 1
    FROM `post_views` existing
    WHERE existing.`post_id` = post.`id`
      AND existing.`viewer_key` = CONCAT('demo-seed-viewer-', n.`k`)
      AND existing.`created_at` >= @activity_day
      AND existing.`created_at` < @activity_day + INTERVAL 1 DAY
);

-- 7. 어제 인기글 확정
--    배치는 활동이 없는 날도 0건으로 확정한다. 서버가 어제 00:05 이후에 0건으로 확정해 두었으면
--    그 기록만 지우고 다시 확정한다. 1건 이상 확정된 날은 배치 규칙(D4)대로 건드리지 않는다.
DELETE FROM `popular_post_batch_runs`
WHERE `ranking_date` = @activity_day
  AND `post_count` = 0;

-- 아래 SELECT 는 CommunityPopularPostMapper.xml 의 insertDailyRanking 을 그대로 옮긴 것이다.
-- 가중치(조회 1 · 좋아요 25 · 댓글 쓴 사람 15)와 하루 창, PUBLISHED 조건이 그쪽 정본을 따른다.
-- 정본이 바뀌면 이 절과 seed-community.sql 8절을 함께 고친다.
INSERT INTO `daily_popular_posts`
        (`ranking_date`, `ranking`, `post_id`, `popularity_score`,
         `view_count`, `like_count`, `comment_count`)
SELECT @activity_day,
       ROW_NUMBER() OVER (ORDER BY s.`popularity_score` DESC, s.`post_id` DESC),
       s.`post_id`,
       s.`popularity_score`,
       s.`view_count`,
       s.`like_count`,
       s.`comment_count`
  FROM (
        SELECT e.`post_id`,
               SUM(e.`view_count`)    AS `view_count`,
               SUM(e.`like_count`)    AS `like_count`,
               SUM(e.`comment_count`) AS `comment_count`,
               SUM(e.`view_count`) * 1
                 + SUM(e.`like_count`) * 25
                 + SUM(e.`comment_count`) * 15 AS `popularity_score`
          FROM (
                SELECT `post_id`, 1 AS `view_count`, 0 AS `like_count`, 0 AS `comment_count`
                  FROM `post_views`
                 WHERE `created_at` >= @activity_day
                   AND `created_at` <  @activity_day + INTERVAL 1 DAY

                UNION ALL

                SELECT `post_id`, 0, 1, 0
                  FROM `post_likes`
                 WHERE `created_at` >= @activity_day
                   AND `created_at` <  @activity_day + INTERVAL 1 DAY

                UNION ALL

                SELECT `post_id`, 0, 0, 1
                  FROM (
                        SELECT `post_id`, `member_id`
                          FROM `comments`
                         WHERE `status` = 'PUBLISHED'
                           AND `created_at` >= @activity_day
                           AND `created_at` <  @activity_day + INTERVAL 1 DAY
                         GROUP BY `post_id`, `member_id`
                       ) commenters
               ) e
          JOIN `posts` p
            ON p.`id` = e.`post_id`
           AND p.`status` = 'PUBLISHED'
         GROUP BY e.`post_id`
         ORDER BY `popularity_score` DESC, e.`post_id` DESC
         LIMIT 20
       ) s
 WHERE NOT EXISTS (
       SELECT 1 FROM `popular_post_batch_runs` run WHERE run.`ranking_date` = @activity_day
 )
   AND NOT EXISTS (
       SELECT 1 FROM `daily_popular_posts` ranked WHERE ranked.`ranking_date` = @activity_day
 );

-- COUNT(*) 는 행이 없어도 한 줄을 돌려주므로, 이미 확정된 날짜를 거르는 조건은 바깥에 둔다.
INSERT INTO `popular_post_batch_runs` (`ranking_date`, `post_count`)
SELECT @activity_day, ranked.`post_count`
  FROM (
        SELECT COUNT(*) AS `post_count`
          FROM `daily_popular_posts`
         WHERE `ranking_date` = @activity_day
       ) ranked
 WHERE NOT EXISTS (
       SELECT 1 FROM `popular_post_batch_runs` run WHERE run.`ranking_date` = @activity_day
 );

COMMIT;

DROP TEMPORARY TABLE `_demo_community_posts`;
DROP TEMPORARY TABLE `_demo_community_comments`;
DROP TEMPORARY TABLE `_demo_numbers`;
