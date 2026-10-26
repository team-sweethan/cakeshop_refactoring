# 학원 서버 배포

AWS 없이 MariaDB와 서버 디스크를 사용하는 `server` 프로필을 제공한다. 아래는 JDK 21에서 실행 가능한
JAR 배포 기준이다. 학원 서버의 OS·포트·DB 계정·프로세스 관리 방식은 배정받은 환경에 맞춘다.
외부 Tomcat에 WAR로 올려야 하는 환경은 현재 빌드와 다르므로 별도 구성이 필요하다.

## 빌드와 실행

개발 PC에서 빌드한다. 테스트 환경은 [테스트 규칙](testing.md)을 따른다.

```bash
./gradlew bootJar
```

`build/libs/cakeshop-0.0.1-SNAPSHOT.jar`와 `deploy/server.env.example`을 서버의 실행 디렉터리에 복사한다.
예제 환경 파일을 `.env`로 복사하고 DB 정보·허용 포트·영구 업로드 경로를 수정한다. 기존 `.env`가 있으면
덮어쓰지 말고 백업 후 내용을 반영한다. `.env`는 Spring properties 형식으로 읽으며 shell에서 source하지 않는다.
실제 비밀번호가 들어간 파일은 Git에 올리지 않고 실행 계정만 읽을 수 있게 한다.

`FILE_UPLOAD_DIR`은 JAR 교체 시 삭제되지 않는 절대 경로여야 한다. 실행 계정이 해당 경로에 파일을
생성할 수 있도록 준비한다. 서버에 Java 21과 접근 가능한 MariaDB가 있어야 한다.

JAR과 `.env`가 있는 디렉터리에서 실행한다. 서비스 관리자를 사용한다면 작업 디렉터리도 이곳으로 지정한다.

```bash
java -jar cakeshop-0.0.1-SNAPSHOT.jar --spring.profiles.active=server
```

환경변수 `SPRING_PROFILES_ACTIVE`나 이전 서비스 실행 인수에 `rds`, `s3`, `local`이 남아 있지 않은지
확인한다. `server`는 이 프로필들과 조합하지 않는다. 업로드 파일은 `/uploads/**`에서 제공된다.
기본 포트는 8080이며 `.env`의 `SERVER_PORT`로 변경한다.

## 스키마와 기존 데이터

`server`의 Flyway 기본값은 비활성화다. 이미 사용 중인 학원 공용 DB를 기동만으로 변경하지 않는다.

**새로 배정받은 빈 전용 DB**라면 먼저 UTF-8 데이터베이스를 준비하고 해당 DB에만 접근 가능한 계정을
사용한다. 테이블 생성·변경 권한을 확인한 뒤 최초 기동에서만 Flyway를 켠다.

```bash
java -jar cakeshop-0.0.1-SNAPSHOT.jar --spring.profiles.active=server --spring.flyway.enabled=true
```

이후에는 일반 실행 명령을 사용한다. 이후 migration 반영도 DB 백업과 반영 범위를 확인한 뒤 명시적으로
실행한다. 샘플 seed는 자동 적용하지 않으며 공개 서버에 로컬 테스트 계정을 넣지 않는다.

기존 DB를 옮기는 경우 테이블·데이터와 `flyway_schema_history`를 함께 보존하고 migration 일치 여부를
확인한다. UTC로 저장된 기존 DATETIME은 연결 시간대를 KST로 바꾸어도 자동 보정되지 않으므로 이관 전
기존 저장 기준을 확인한다. 기존 데이터를 지우거나 `baseline-on-migrate`로 검증을 우회하지 않는다.

기존 S3 이미지 URL은 프로필 변경으로 복구되지 않는다. 별도 이미지 백업이 있다면 업로드 디렉터리로
복사하고 DB의 참조 경로를 함께 이관한다. [파일 저장소 가이드](file-upload-storage.md)를 참고한다.

## 외부 연동과 확인

- 메일 인증을 사용하려면 `MAIL_*`, 결제를 사용하려면 일치하는 `TOSS_CLIENT_KEY`·`TOSS_SECRET_KEY`를 입력한다.
- 소셜 로그인은 선택 사항이다. 사용할 때 `server,oauth`로 실행하고 Google·카카오 키 네 값을 모두 입력한다.
  각 제공자 콘솔의 리디렉션 URI도 실제 공개 주소의 `/login/oauth2/code/google`, `/login/oauth2/code/kakao`로 갱신한다.
- 역방향 프록시에서 HTTPS를 종료한다면 외부 요청의 전달 헤더를 프록시가 덮어쓰고 앱 직접 접근을 제한한 뒤
  `SERVER_FORWARD_HEADERS_STRATEGY=native` 등을 해당 프록시 구성에 맞게 설정한다.

기동 후 `http://서버주소:포트/actuator/health`와 홈, 로그인, 이미지 업로드·조회, 채팅을 확인한다.
재시작 후에도 업로드 파일이 유지되는지 확인한다. 소셜 로그인·메일·결제를 설정했다면 실제 공개 주소에서도
각 흐름을 확인한다. 개발용 공개 미리보기와 익명 지표는 `server`에서 비활성화되어 있다.
