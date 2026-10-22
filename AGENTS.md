# cakeshop 에이전트 가이드

이 파일은 Codex와 Claude Code가 공유하는 **프로젝트 진입점**이다. 도메인 담당 배정, 항상 적용되는
불변 규칙과 작업별 정본의 위치만 제공한다. 규칙의 세부 기준은 한 정본에만 두며, 이 파일은 다른
정본의 내용을 요약하거나 다시 해석하지 않는다.

## 컨텍스트 로딩 원칙

1. 요청을 작업 유형과 영향 도메인으로 먼저 분류한다.
2. 아래 표에서 **주 작업 하나만** 선택하고 첫 번째 라우터만 읽는다.
3. Java·DTO·테스트 같은 수정 파일 종류만으로 다른 행이나 공통 문서를 누적 적용하지 않는다.
4. 첫 번째 라우터와 현재 코드만으로 결정을 내릴 수 없을 때만 필요한 정본의 관련 절을 추가로 읽는다.
5. `docs/` 전체나 긴 문서 전체를 한꺼번에 읽지 않는다.
6. 문서가 `정본`으로 명시한 결정과 코드가 충돌하면 코드로 우회하지 말고 충돌을 알린다.
7. 결정되지 않은 업무 규칙은 추측해서 구현하지 않는다.

| 주 작업 | 첫 번째 라우터 |
|---|---|
| `member` 기능 개발 | `docs/member/DOMAIN.md` |
| `cart` 기능 개발 | `docs/cart/DOMAIN.md` |
| `community` 기능 개발 | `src/main/java/com/cakeshop/domain/community/CLAUDE.md`의 라우팅 지침 |
| `review` 후기 기능 개발 | `src/main/java/com/cakeshop/domain/review/CLAUDE.md`의 라우팅 지침 |
| 코드·PR 리뷰, 커밋·PR·병합 | `docs/pull-request.md` |
| 그 밖의 도메인 기능 개발 | 담당자가 만든 라우터가 있으면 사용한다. 없으면 임의로 만들지 않고 현재 코드와 필요한 정본만 확인한다. |

`review`라는 말은 이 저장소에서는 기본적으로 **후기 기능 도메인**을 뜻한다. 코드나 PR 검토는 사용자가
`코드 리뷰`, `PR 리뷰`처럼 명시한 경우로 구분한다.

### 필요할 때만 추가하는 공통 정본

- 새 패키지·계층·DTO·Service·MyBatis·Security 구조를 **결정해야 할 때** `docs/conventions.md`의 관련 절
- 테스트의 추가 여부·계층 선택·중복 정리·fixture를 **결정해야 할 때** `docs/testing.md`의 관련 절
- DB·Flyway·seed 구조를 바꿀 때 `docs/conventions.md` 7~8절과 `docs/flyway_make_sample.md`,
  기존 스키마 확인은 `docs/database-schema.md`에서 연결되는 해당 `docs/schema/<domain>.md`
- 도메인 간 계약이나 ReadModel을 설계할 때 `docs/conventions.md` 12절
- Thymeleaf 화면·프래그먼트·정적 자원을 작성·수정할 때 `docs/frontend-template-format.md`
- 화면의 웹 접근성 동작을 설계·수정·검증할 때 `docs/web-accessibility.md`
- 아직 합의되지 않은 연동 정책을 만났을 때 `docs/team-plan.md`
- 자체 PG 연동 작업에서 되돌리기 어렵거나 대안 중 하나를 고르는 결정을 만났을 때
  `docs/order/pg-decisions/README.md`

기존 코드와 도메인 라우터가 이미 답을 주는 일반적인 Java·DTO 수정이나 테스트 추가에는 공통 문서를 다시
읽지 않는다. 다른 도메인의 라우터는 해당 담당자가 필요할 때 추가한다.

## 프로젝트 불변 규칙

- Java 21, Spring Boot 4, Spring MVC, Thymeleaf, Spring Security, MyBatis, Flyway, MariaDB를 사용한다.
  정확한 버전과 의존성은 `build.gradle`이 정본이다.
- 운영 코드는 `src/main/java/com/cakeshop`, MyBatis XML은 `src/main/resources/mapper/<domain>`, 화면은
  `src/main/resources/templates/{admin,customer,auth,home,error,fragments}`, 테스트는 `src/test/java`에 둔다.
- 기능은 `domain/<domain>/{controller,service,mapper,entity,dto,error}` 수직 슬라이스로 구성한다.
- 의존 방향은 `Controller -> Service -> Mapper -> DB`다. 계층을 건너뛰거나 역방향으로 참조하지 않는다.
- 둘 이상의 도메인이 실제로 공유하는 기반 코드만 `global`에 둔다.
- `bin`, `build`, `out`은 생성 결과물이므로 직접 수정하지 않는다.
- 공유된 versioned migration은 수정하지 않는다. 새 migration은 Gradle `newMigration` 태스크로만
  생성하며, 명령과 작성 기준은 `docs/flyway_make_sample.md`를 따른다.

## 도메인 경계와 담당

도메인은 다른 도메인의 테이블·Mapper·Entity를 직접 사용하지 않고, 데이터 소유 도메인의 공개 Service와
최소 DTO를 통해 연동한다. 연동 계약의 이름과 협업 기준, ReadModel 예외의 허용 조건은
`docs/conventions.md` 12절이 정본이고, PR에서의 합의 기록과 리뷰어 지정 절차는
`docs/pull-request.md` 1절을 따른다.

| 담당 | 도메인 |
|---|---|
| 수민 | `member`, `cart` |
| 주환 | `order`, `payment` |
| 정후 | `coupon` |
| 민정 | `chat`, `notification` |
| 현규 | `community`, `review` |
| 시은 | `product`, `dashboard`, `statistics` |
| 공통 협의 | `store`, `global`, `home` |

## 작업과 검증 루프

1. 요청, 현재 코드와 정본에서 완료 조건과 변경 범위를 정한다.
2. 범위 안의 가장 작은 변경을 구현한다.
3. 변경 위험을 직접 검증하는 테스트를 먼저 실행한다.
4. 트랜잭션·DB·화면·보안처럼 영향 범위가 넓으면 관련 통합 테스트와 전체 테스트로 확장한다.
5. 실패 결과를 원인과 연결해 수정하고, 같은 검증을 다시 실행한다.
6. 완료 시 변경 파일, 실행한 검증, 검증하지 못한 항목과 남은 위험을 보고한다.

어떤 테스트를 추가하거나 생략할지, 계층별 검증 책임과 DB 테스트 실행 환경은 `docs/testing.md`를 따른다.

## 보안과 데이터 무결성

모든 변경에 적용되는 최소 불변 규칙이다. 세부 기준과 예외는 `docs/conventions.md` 6·7·10절이 정본이다.

- 요청으로 전달된 회원 ID·권한·상태·금액을 신뢰하지 않는다. Service에서 인증 사용자와 자원 소유권을
  검증한다.
- 관리자 기능은 화면 숨김이 아니라 Spring Security의 `ADMIN` 권한으로 강제한다.
- 비밀번호, 인증 정보, 개인정보, SQL과 내부 경로를 응답이나 로그에 노출하지 않는다.
- MyBatis 사용자 입력은 `#{}`로 바인딩하고 `${}`를 사용하지 않는다.
- 여러 쓰기와 상태 전이는 공개 Service의 단일 트랜잭션에서 현재 상태와 목표 상태를 검증한다.

## Code Review Rules

코드·PR 리뷰에서는 `docs/pull-request.md` 5절을 읽고 그대로 따른다. 영향받는 도메인의 정본이 따로 있으면
해당 라우터를 통해 필요한 규칙만 추가로 읽는다.
