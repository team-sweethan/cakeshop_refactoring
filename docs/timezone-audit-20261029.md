# CakeShop 시간대 점검·수정 (2026-10-29)

## 확인 범위

CakeShop 로컬 소스·JVM UTC 테스트·격리 MariaDB를 확인했다. 실제 AWS 인스턴스·배포 버전·운영 DB에는 접속하지 않았다.
서버 로그에 Z/UTC가 표시되는 것과 업무 시각이 잘못 저장되는 것은 구분한다. JVM 전체 시간대를 바꾸는 방식 대신 업무 시계·표시 규칙을 명시한다.

## 발견한 문제와 수정

- ChatService, NotificationService, NotificationDeliveryService에 LocalDateTime.now()가 남아 있었다.
  JVM이 UTC이면 한국 DATETIME DB 세션에서 만든 시각과 9시간 달라질 수 있었다.
  기존 Asia/Seoul Clock을 주입받아 LocalDateTime.now(clock)로 생성하도록 바꿨다.
- 채팅 JS는 new Date(...).getHours() 등 브라우저 기본 시간대로 표시했다.
  korea-time.js에서 offset 없는 DATETIME 문자열은 한국 벽시각(+09:00)으로 해석하고,
  Z/offset이 있는 문자열은 같은 순간의 Asia/Seoul 시각으로 표시한다.
  채팅 시간·채팅 안 픽업 시각·알림 날짜 표시와 관리자 채팅 정렬에 적용했다.
- TossPaymentClient는 OffsetDateTime.toLocalDateTime()으로 offset을 바로 버렸다.
  UTC 또는 다른 offset 응답이면 잘못된 벽시각을 저장할 수 있어 Asia/Seoul로 같은 순간을 변환한 뒤 LocalDateTime으로 만든다.
  승인·취소·조회 응답에 적용하고 기존 +09:00 응답 동작은 유지한다.

## 주문·결제 점검 결과

OrderService·CustomerCustomOrderService·FulfillmentService·주문 만료·제작 완료·결제·환불 서비스의 현재 시각 생성은 이미 주입 Clock을 사용한다.
공용 ClockConfig.clock과 OrderTimeConfig.orderClock은 모두 Asia/Seoul이다. 해당 시간 생성 코드는 이번에 바꾸지 않았다.
서울 오전/오후를 기준으로 동작하는 픽업 알림 cron은 이미 zone=Asia/Seoul이다. 고정 간격 배치는 특정 지역의 정각을 의미하지 않는다.
local/rds/server JDBC URL은 이미 connectionTimeZone=+09:00 및 forceConnectionTimeZoneToSession=true를 사용한다.
따라서 채팅·알림 SQL의 NOW()/CURRENT_TIMESTAMP는 그 세션 설정에서 한국 벽시각을 생성한다. SQL·스키마를 변경할 필요는 없었다.

DonWorry 전송 Instant는 UTC 표현을 유지한다. 예를 들어 한국 15시는 06:00Z이며 이를 15:00Z로 바꾸면 실제 순간이 달라진다.
UI 표시를 한국 시각으로 하는 것과 서버 간 Instant 전송을 구분한다.

## 이번 시간대 수정 파일

| 파일 | 변경 |
| --- | --- |
| src/main/java/com/cakeshop/domain/chat/service/ChatService.java | 채팅·메모·첨부 시각 생성에 Clock 주입 |
| src/main/java/com/cakeshop/domain/notification/service/NotificationService.java | 알림·묶음 갱신·발송 완료 시각 생성에 Clock 주입 |
| src/main/java/com/cakeshop/domain/notification/service/NotificationDeliveryService.java | 발송 예약·스킵 이력 시각 생성에 Clock 주입 |
| src/main/java/com/cakeshop/domain/payment/infra/TossPaymentClient.java | 외부 승인·취소·조회 시각을 서울로 변환 |
| src/main/resources/static/js/korea-time.js | 공용 한국 시각 해석·표시 함수 추가 |
| src/main/resources/static/js/chat-admin.js | 관리자 채팅 시간·픽업 표시·정렬에 공용 함수 사용 |
| src/main/resources/static/js/chat-customer.js | 고객 채팅 시간·픽업 표시 공용 함수 사용 |
| src/main/resources/templates/admin/chat/list.html | 한국 시각 JS 로딩 |
| src/main/resources/templates/customer/chat/room.html | 한국 시각 JS 로딩 |
| src/main/resources/templates/admin/notification/list.html | 한국 시각 JS 로딩·목록 날짜 포맷 적용 |
| src/main/resources/templates/customer/notification/list.html | 한국 시각 JS 로딩·목록 날짜 포맷 적용 |
| src/test/java/com/cakeshop/domain/chat/service/ChatServiceTimeTests.java | 채팅 생성 시각 회귀 검사 추가 |
| src/test/java/com/cakeshop/domain/notification/service/NotificationTimeTests.java | 알림·발송 예약 시각 회귀 검사 추가 |
| src/test/java/com/cakeshop/domain/payment/infra/TossPaymentClientTests.java | UTC 승인·취소·조회 응답 회귀 검사 추가 |
| src/test/java/com/cakeshop/customer/ScreenRenderingTests.java | 네 화면 렌더링·DB 세션 +09:00 검사 추가 |
| src/test/javascript/korea-time.test.cjs | UTC/서울/미국 환경별 표시 회귀 검사 추가 |
| docs/timezone-audit-20261029.md | 점검 근거·변경 파일·검증·배포 제한 기록 |

직전에 작업한 DonWorry 픽업 송신부 파일과 이번 수정 목록은 별개다. 변경은 CakeShop 저장소 로컬에만 보관한다.

## 검증

- UTC JVM의 채팅·알림·외부 결제·Clock·프로필 설정 관련 23개 검사 통과.
- 분리된 시간대 수정 브랜치의 전체 unitTest 1,405개 성공, 실패·건너뜀 0.
- node --test src/test/javascript/korea-time.test.cjs: UTC/Asia/Seoul/America/Los_Angeles의 3개 검사 통과.
- node --check: 채팅 고객/관리자 JS 구문 검사 통과.
- UTC JVM의 ScreenRenderingTests 두 메서드 모두 통과. 고객/관리자 채팅·알림 화면 네 곳 렌더링과 실제 JDBC 세션 +09:00을 확인했다.
- 분리 브랜치의 전체 MariaDB 검사 729개가 모두 통과했다. 실제 브라우저 상호작용·AWS 운영 환경은 검증하지 않았다.

## 배포와 기존 데이터

이 수정은 재배포 후 생성되는 시각과 새 화면 코드에 적용된다. 현재 AWS에 어떤 커밋·프로필·환경변수가 배포됐는지는 별도 확인이 필요하다.
환경변수로 spring.datasource.url을 덮어쓰면 저장소의 시간대 옵션이 빠질 수 있으므로 운영 DB 연결의 @@session.time_zone을 확인해야 한다.
이미 UTC 벽시각으로 잘못 저장된 DATETIME 행은 설정 변경으로 자동 보정되지 않는다.
정상 KST 기록과 섞여 있을 수 있으므로 일괄 9시간 추가는 하지 않았다. 배포 시기·원본 로그와 대조해 대상 행이 확정된 경우에만 별도 보정한다.

## PR 분리 후 최종 검사

dev f63692d0 기반 fix/kst-chat-notification-pr에서 단위 1,405개·MariaDB 729개·Node 표시 검사 3개가 모두 통과했다. 실패·오류·스킵 0. 픽업 송신 코드를 포함하지 않는 독립 브랜치다.
