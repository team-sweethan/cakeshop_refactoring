# cakeshop

오프라인 케이크 매장의 상품 탐색부터 주문·결제·회원·커뮤니티·후기·관리자 운영까지 하나의 흐름으로 구현하는 Spring Boot 기반 웹 애플리케이션입니다.

> Java 21 · Spring Boot 4.0.2 · Spring MVC · Thymeleaf · Spring Security · MyBatis · Flyway · MariaDB

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Contributing](#contributing)
- [Architecture & Developer Playbook](#architecture--developer-playbook)
- [File Upload Storage](#file-upload-storage)

## Overview

cakeshop은 매장에 방문하기 전에 상품과 옵션을 살펴보고 주문할 수 있는 고객 경험과, 상품·주문·결제·쿠폰·회원·콘텐츠를 관리하는 운영 경험을 함께 다루는 팀 프로젝트입니다.

이 프로젝트는 다음 목표를 중심으로 개발합니다.

- 고객과 관리자의 실제 사용 흐름을 서버 렌더링 웹 애플리케이션으로 완성합니다.
- 기능별 수직 슬라이스와 명시적인 도메인 경계로 여러 사람이 안전하게 협업합니다.
- Flyway migration과 MariaDB 기반 통합 테스트로 개발 환경과 운영 스키마의 차이를 줄입니다.
- 인증·인가, 트랜잭션, 상태 전이, 결제 복구처럼 서비스 운영에 필요한 무결성을 코드와 테스트로 보장합니다.

## Features

- 상품·옵션 조회와 장바구니
- 주문·결제와 결제 복구
- 쿠폰 발급·사용
- 회원 가입·로그인·회원 관리
- 커뮤니티 게시글·댓글·좋아요·신고
- 상품 후기·평점·관리자 답글
- 알림·채팅
- 매장 정보와 관리자 통계·대시보드

## Tech Stack

| 구분 | 기술 및 버전 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.2, Spring MVC |
| View | Thymeleaf, Thymeleaf Spring Security Extras, Chart.js |
| Security | Spring Security, Spring Security OAuth2 Client |
| Data | MyBatis 4.0.1, MariaDB Connector/J |
| Database Migration | Flyway |
| Realtime | Spring WebSocket |
| Mail | Spring Boot Starter Mail |
| Storage | 로컬 디스크 (기본), AWS SDK for Java v2 S3 (선택) |
| Build | Gradle Wrapper |
| Validation | Jakarta Validation |
| Test | JUnit Platform, AssertJ, Mockito, MockMvc, Spring Security Test, Testcontainers for MariaDB |
| Development | Spring Boot DevTools, Lombok, Spring Boot Actuator, Micrometer Prometheus Registry |

정확한 의존성 버전과 구성은 [`build.gradle`](build.gradle)을 기준으로 합니다.

## Getting Started

### 사전 준비

| 도구 | 용도 |
|---|---|
| Git | 저장소 내려받기 |
| JDK 21 | 애플리케이션 빌드와 실행 |
| MariaDB 11.4 | 로컬 개발 데이터베이스 |
| Docker | MariaDB Testcontainers 통합 테스트 실행 시에만 필요 |

Gradle은 저장소의 Wrapper를 사용하므로 별도로 설치할 필요가 없습니다. 애플리케이션만 실행할 때는 Docker가 필요하지 않습니다.

### 1. 저장소 내려받기

```bash
git clone https://github.com/team-sweethan/cakeshop.git
cd cakeshop
```

### 2. 환경 변수 설정

프로젝트 루트의 `.env_sample`을 `.env`로 복사한 뒤 로컬 MariaDB 접속 정보를 입력합니다.

```powershell
# Windows PowerShell
Copy-Item .env_sample .env
```

```bash
# macOS / Linux
cp .env_sample .env
```

기본 개발 환경은 로컬 MariaDB와 로컬 디스크를 사용하며 AWS·OAuth 설정이 필요하지 않습니다.

```dotenv
LOCAL_DB_HOST=localhost
LOCAL_DB_PORT=3306
LOCAL_DB_DATABASE=cakeshop
LOCAL_DB_USERNAME=your-username
LOCAL_DB_PASSWORD=your-password
```

`.env_sample`의 기본 포트는 `3307`입니다. 설치된 MariaDB 포트에 맞게 수정합니다.
업로드 경로를 지정하지 않으면 `<user home>/cakeshop-uploads`를 사용합니다.
학원 서버 배포는 개발 프로필 대신 [서버 배포 가이드](docs/server-deployment.md)를 따릅니다.

### 3. 로컬 데이터베이스 생성

MariaDB에서 다음 SQL을 한 번 실행합니다. 이후 테이블과 제약조건은 애플리케이션 시작 시 Flyway가 생성합니다.

```sql
CREATE DATABASE `cakeshop`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
```

애플리케이션용 계정에는 `cakeshop` 데이터베이스의 테이블 생성·변경과 데이터 읽기·쓰기 권한이 필요합니다.

### 4. 애플리케이션 실행

로컬 MariaDB와 로컬 파일 저장소만 사용하려면 다음과 같이 `local` 프로필을 지정합니다.

```powershell
# Windows
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

```bash
# macOS / Linux
./gradlew bootRun --args="--spring.profiles.active=local"
```

별도 인수 없이 실행해도 `local` 프로필을 사용합니다.

```bash
./gradlew bootRun
```

AWS를 다시 사용할 때만 `rds` 또는 `s3` 프로필을 명시하고 해당 접속 정보를 설정합니다.
현재 학원 서버 배포에는 두 프로필을 사용하지 않습니다.

Google·카카오 소셜 로그인은 `.env`에 각 서비스의 Client ID와 Client Secret을 입력하고 `oauth` 프로필을
추가합니다. Google Cloud Console의 승인된 리디렉션 URI에는
`http://localhost:8080/login/oauth2/code/google`을, 카카오 개발자 콘솔의 Redirect URI에는
`http://localhost:8080/login/oauth2/code/kakao`를 등록합니다.

```powershell
# Windows: 로컬 MariaDB + 로컬 디스크 + Google/Kakao OAuth
.\gradlew.bat bootRun --args="--spring.profiles.active=local,oauth"
```

`http://localhost:8080`에 접속해 화면이 열리는지 확인합니다.

### 5. 샘플 데이터 입력(선택)

샘플 데이터는 Flyway 관리 대상이 아니므로 애플리케이션 실행 후 별도로 입력합니다. MariaDB 클라이언트에서 다음 순서로 실행합니다.

```sql
SOURCE src/main/resources/db/seed/seed-local.sql;
SOURCE src/main/resources/db/seed/seed-community.sql;
SOURCE src/main/resources/db/seed/seed-review.sql;
```

`seed-local.sql`이 회원·상품을 먼저 준비하므로 실행 순서를 바꾸지 않습니다. `seed-community.sql`과
`seed-review.sql`은 각각 커뮤니티와 후기 확인용 데이터가 필요할 때만 실행해도 됩니다. 경로는 MariaDB
클라이언트를 시작한 위치에 맞게 절대 경로로 바꿔도 됩니다.

### 6. 빌드와 테스트

```powershell
# Windows
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat unitTest
.\gradlew.bat mariaDbTest
```

```bash
# macOS / Linux
./gradlew build
./gradlew test
./gradlew unitTest
./gradlew mariaDbTest
```

`test`는 전체 테스트, `unitTest`는 MariaDB Testcontainers 제외, `mariaDbTest`는 MariaDB 통합 테스트만
실행합니다. `test`와 `mariaDbTest`에는 Testcontainers 테스트가 포함되므로 Docker가 실행 중이어야
합니다. CI는 `unitTest`와 `mariaDbTest`를 서로 다른 러너에서 병렬 실행한 뒤 기존 필수 체크 `Test`로
결과를 합칩니다.

### 실행 프로필

| 프로필 | 용도 | Flyway |
|---|---|---|
| `local` (기본) | 개인 PC의 MariaDB와 로컬 디스크 | 활성화 |
| `server` | 학원 서버 MariaDB와 영구 로컬 디스크 | 기본 비활성화, 최초 구축 시 명시적으로 활성화 |
| `local,s3` | AWS를 별도로 준비한 경우 로컬 MariaDB와 S3 | 활성화 |
| `rds` | 팀 공용 AWS RDS 연결 | 비활성화 |
| `rds,s3` | 팀 공용 AWS RDS와 공용 S3 연결 | 비활성화 |
| `oauth` | Google·카카오 OAuth 클라이언트와 공통 OAuth 로그인 처리 활성화. 단독 사용하지 않고 `server,oauth` 또는 `local,oauth`처럼 조합 | 조합 대상 프로필을 따름 |

공용 RDS 스키마는 애플리케이션 시작으로 변경하지 않습니다. `rds` 프로필은 접속 정보와 별도의 스키마 반영 절차가 준비된 경우에만 사용합니다.

### 자주 발생하는 문제

- `Access denied`: `.env`의 계정·비밀번호와 MariaDB 권한을 확인합니다.
- `Unknown database 'cakeshop'`: 위의 데이터베이스 생성 SQL을 먼저 실행합니다.
- `Port 8080 was already in use`: 기존 애플리케이션 프로세스를 종료한 뒤 다시 실행합니다.
- `Found non-empty schema ... but no schema history table`: 개인 로컬 DB를 백업한 뒤 빈 `cakeshop` 데이터베이스로 다시 시작합니다. 공용 RDS를 초기화해서는 안 됩니다.
- Testcontainers 연결 실패: Docker Desktop 또는 Docker Engine이 실행 중인지 확인합니다.

## Quick Start

설치와 샘플 데이터 입력을 마쳤다면 다음 흐름으로 주요 화면을 확인할 수 있습니다.

1. `http://localhost:8080`에서 고객 홈과 상품 목록을 확인합니다.
2. `user@cakeshop.local` / `Admin1234!`로 로그인해 장바구니·주문·커뮤니티·후기 기능을 확인합니다.
3. `admin@cakeshop.local` / `Admin1234!`로 로그인해 관리자 기능을 확인합니다.
4. 코드를 수정한 뒤 테스트를 실행해 변경이 기존 동작을 깨뜨리지 않는지 확인합니다.

샘플 계정은 로컬 확인 전용입니다. 공용 또는 운영 환경에서 사용하지 않습니다.

## Project Structure

```text
src/
├── main/
│   ├── java/com/cakeshop/
│   │   ├── domain/<domain>/
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── mapper/
│   │   │   ├── entity/
│   │   │   ├── dto/{form,view}/
│   │   │   └── error/
│   │   └── global/
│   └── resources/
│       ├── db/{migration,seed}/
│       ├── mapper/<domain>/
│       └── templates/{admin,customer,auth,home,error,fragments}/
└── test/
    ├── java/
    └── resources/
```

요청은 기본적으로 `Controller → Service → Mapper → DB` 방향으로 흐릅니다. 도메인 간에는 상대 도메인의 테이블·Mapper·Entity를 직접 참조하지 않고 공개 Service 계약을 사용합니다.

## Contributing

1. 하나의 브랜치와 PR에는 하나의 목적만 담습니다.
2. 구현 전에 [`docs/conventions.md`](docs/conventions.md)의 계층·명명·도메인 경계 규칙을 확인합니다.
3. 테스트는 [`docs/testing.md`](docs/testing.md)의 이름과 범위 규칙을 따릅니다.
4. 새 Flyway migration은 파일명을 직접 만들지 않고
   [Flyway migration 작성 가이드의 파일 생성](docs/flyway_make_sample.md#2-파일-생성)을 따릅니다.

5. 이미 공유된 versioned migration은 수정하지 않습니다. 변경이 필요하면 새 migration을 추가합니다.
6. 커밋 제목은 `<type>: 한글 요약` 형식을 사용합니다. 예: `feat: 후기 작성 기능 추가`.
7. PR에는 변경 목적, 주요 변경, 테스트 결과, DB 영향, 집중 리뷰 사항, 관련 이슈를 적습니다.

세부 브랜치·리뷰·병합 규칙은 [`docs/pull-request.md`](docs/pull-request.md)를 따릅니다.

## 🏛️ Architecture & Developer Playbook

### 기술 선택 배경

- 🔐 **[세션 기반 인증을 선택한 이유](docs/decisions/session-authentication.md)**: SSR과 세션의 관계, 폼 로그인·강제 세션 만료를 선택한 근거, JWT를 다시 검토할 조건
- 🧩 **[도메인 중심 수직 슬라이스 구조를 선택한 이유](docs/decisions/domain-oriented-architecture.md)**: DDD에서 채택한 경계와 계약, 전술적 DDD 전체를 적용하지 않은 이유와 비용
- 🗄️ **[Flyway로 데이터베이스 변경을 관리하는 이유](docs/decisions/flyway-migrations.md)**: 스키마 재현·이력·검증의 근거, 수동 SQL·자동 생성과의 비교, MariaDB와 RDS에서의 한계

### 개발 규칙과 가이드

- 🧭 **[코드 컨벤션과 아키텍처](docs/conventions.md)**: 기술 기준, 수직 슬라이스, 네이밍, DB·MyBatis·트랜잭션·인증 규칙과 도메인 연동 경계
- 🧪 **[테스트 작성 가이드](docs/testing.md)**: 계층별 테스트 전략, MariaDB Testcontainers, 테스트 대역, 명명과 검증 기준
- 🗃️ **[Flyway migration 작성 가이드](docs/flyway_make_sample.md)**: 변경 단위, 기존 데이터 영향, MariaDB DDL 실패·복구와 검증 기준
- 🧱 **[데이터베이스 스키마 명세](docs/database-schema.md)**: 현재 Flyway 적용 결과를 담당자·도메인·테이블별로 정리한 최종 스키마
- 🔄 **[상태값 규칙](docs/conventions.md#11-상태값)**: Enum 저장값, DB 제약, Service 전이와 동시성 검증의 공통 기준
- 🤝 **[팀 미결정 항목](docs/team-plan.md)**: 여러 담당자가 함께 결정해야 하는 도메인 연동과 운영 환경 질문
- 🔀 **[Pull Request 가이드](docs/pull-request.md)**: PR 크기·제목·본문, 리뷰 요청과 브랜치별 병합 기준
- 🎨 **[Thymeleaf 화면 작성 규칙](docs/frontend-template-format.md)**: 고객·관리자 화면 구조, 프래그먼트 계약, 정적 자원과 렌더링 검증 기준
- ♿ **[웹 접근성 가이드라인](docs/web-accessibility.md)**: WCAG 2.2 AA 개발 목표, 키보드·포커스·상태 메시지와 자동·수동 검증 기준
- 🖼️ **[파일 업로드 저장소 가이드](docs/file-upload-storage.md)**: 프로필별 로컬·S3 저장소 선택, 자격 증명·prefix 설정과 환경 간 이관 주의사항

## File Upload Storage

업로드 호출부는 공통 `FileStorageClient`를 사용하며, 기본 개발 환경인 `local`과 학원 서버용
`server`에서는 로컬 디스크에 저장합니다. 프로필 조합, 로컬 디스크 설정, AWS 자격 증명과 환경 간 파일 이관 방법은
[`파일 업로드 저장소 가이드`](docs/file-upload-storage.md)를 참고합니다.
