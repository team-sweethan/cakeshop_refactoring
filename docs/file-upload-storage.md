# 파일 업로드 저장소 가이드

이 문서는 cakeshop의 파일 저장 추상화, 프로필별 구현 선택, 로컬 디스크와 S3 설정, 환경 간 파일 이관 시
주의사항을 설명한다.

## 저장소 구조

현재 상품·매장 업로드 호출부는 저장 위치를 직접 다루지 않고 공통
[`FileStorageClient`](../src/main/java/com/cakeshop/global/infra/FileStorageClient.java)를 사용한다.

도메인 Service는 `S3StorageService`나 `LocalFileStorageClient` 같은 구체 구현체를 직접 참조하거나
주입하지 않고 반드시 `FileStorageClient` 인터페이스에만 의존한다. Spring은 활성 프로필에 따라 다음
구현체 중 하나를 `FileStorageClient`로 선택한다.

- `s3` 프로필 활성화: [`S3StorageService`](../src/main/java/com/cakeshop/global/storage/S3StorageService.java)
- `s3` 프로필 비활성화: [`LocalFileStorageClient`](../src/main/java/com/cakeshop/global/infra/LocalFileStorageClient.java)

도메인 서비스는 저장 결과로 받은 접근 경로 또는 공개 URL만 DB에 저장한다. 저장소 구현을 바꾸기 위해
상품·매장 같은 도메인 코드를 함께 바꾸지 않는다.

후기 이미지도 `review_images`와 `FileStorageClient`를 사용한다. 작성 시 선택한 이미지는
`FileStorageDirectory.REVIEW` 아래에 저장되며, 활성 프로필에 따라 로컬 경로 또는 S3 공개 URL이
`review_images.image_url`에 기록된다.

## 프로필 조합

| 활성 프로필 | 저장소 | 용도 |
|---|---|---|
| `local,s3` | `S3StorageService` | AWS를 별도로 준비한 경우 로컬 DB와 S3 사용 |
| `local` (기본) | `LocalFileStorageClient` | 로컬 DB와 PC 외부 디렉터리 사용 |
| `server` | `LocalFileStorageClient` | 학원 서버 DB와 영구 디렉터리 사용 |
| `rds` | `LocalFileStorageClient` | RDS와 실행 PC의 로컬 저장소 사용 |
| `rds,s3` | `S3StorageService` | RDS와 S3를 함께 사용하는 배포 환경 |

RDS는 데이터베이스이고 S3는 파일 저장소이므로 서로 독립적으로 선택한다. 프로필을 변경해도 로컬 DB와
RDS의 행이나 이미지 URL이 자동으로 동기화되지 않는다.

## 로컬 디스크 사용

기본 개발 환경은 로컬 디스크를 사용한다. `.env`의 `FILE_UPLOAD_DIR`에 프로젝트 밖의 저장 경로를
지정할 수 있으며, 생략하면 `<user home>/cakeshop-uploads`가 사용된다. 학원 서버용 `server` 프로필은
영구 디렉터리인 `FILE_UPLOAD_DIR`을 필수로 요구한다. [서버 배포 가이드](server-deployment.md)를 따른다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

실제 파일은 `app.file.upload-dir` 아래에 저장하고 DB에는 `app.file.url-prefix`로 시작하는 웹 접근 경로를
저장한다. [`WebConfig`](../src/main/java/com/cakeshop/global/config/WebConfig.java)가 외부 디렉터리의 파일을
해당 URL 경로로 제공한다.

## 선택 환경: 로컬 DB와 S3

`.env_sample`을 복사한 로컬 `.env`에서 다음 설정을 확인한다. 공용 버킷명과 URL은 비밀 값이 아니지만,
실제 Access Key와 Secret Key는 Git에 추적되지 않는 로컬 `.env`에만 입력한다.

```dotenv
AWS_REGION=ap-northeast-2
AWS_ACCESS_KEY_ID=your-local-access-key
AWS_SECRET_ACCESS_KEY=your-local-secret-key
AWS_SESSION_TOKEN=
AWS_S3_BUCKET=sweethan-cakeshop-images
AWS_S3_BASE_URL=https://sweethan-cakeshop-images.s3.ap-northeast-2.amazonaws.com
AWS_S3_KEY_PREFIX=local-your-name
```

로컬 Access Key와 Secret Key는 반드시 함께 설정한다. STS나 IAM Identity Center의 임시 자격 증명을
사용하면 `AWS_SESSION_TOKEN`도 함께 설정한다. Access Key와 Secret Key가 모두 비어 있으면
`aws configure`, 현재 프로세스의 AWS 환경 변수, IAM Role 같은 AWS SDK 기본 자격 증명 체인을 사용한다.

`AWS_S3_KEY_PREFIX`는 `local-본인GitHub아이디`처럼 영문·숫자·점·밑줄·하이픈만 사용해 개발자마다
고유하게 설정하고, RDS 환경은 `rds-dev`처럼 별도 값을 사용한다. 저장소는 현재 prefix로 만든 객체만
삭제하므로 로컬 DB가 다른 환경의 URL을 갖고 있어도 해당 S3 객체를 삭제하지 않는다.

prefix 도입 전에 생성한 S3 객체는 새 환경에서 자동 삭제하지 않는다. 필요하면 참조 여부를 확인한 뒤
버킷에서 별도로 정리한다.

AWS를 다시 준비한 경우에만 `local,s3`를 명시하고 관리자 상품·매장 이미지 업로드로 확인한다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local,s3"
```

## RDS와 S3 함께 사용

애플리케이션 코드를 바꾸지 않고 기존 `RDS_*` 설정과 S3 설정을 준비한 뒤 프로필만 조합한다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=rds,s3"
```

배포 환경은 장기 액세스 키 대신 EC2 Instance Profile 또는 ECS Task Role 같은 IAM Role을 사용한다.
업로드 대상 prefix에 필요한 `s3:PutObject`, `s3:DeleteObject` 등 최소 권한만 부여하고, 운영 환경에서는
CloudFront와 비공개 S3 조합을 우선 검토한다.

## 데이터와 파일 이관

현재 이미지 URL 컬럼은 `VARCHAR(500)`이므로 S3 URL 저장만을 위한 migration은 필요하지 않다.
기존 S3 URL도 로컬 경로로 자동 변환되지 않는다. 내려간 S3의 파일은 별도 백업이 없으면 복구할 수 없다.
로컬 디스크로 옮길 때는 파일 복사와 해당 DB URL 갱신을 함께 계획한다.

기존 `/uploads/...` 파일은 자동 이전되지 않으므로 S3 복사와 DB URL 변경을 별도 이관 작업으로 진행해야
하며, 공유 RDS 데이터 변경은 백업과 팀 승인 후 수행한다.

같은 S3 버킷을 사용하더라도 각 DB에 상품·매장 정보와 `image_url`이 존재해야 화면에서 조회할 수 있다.
RDS에 S3 URL을 저장하기 시작한 뒤에는 로컬 경로와 S3 URL이 섞이지 않도록 `rds,s3` 조합을 사용한다.

버킷이나 CloudFront 기본 URL을 변경해도 DB에 이미 저장된 전체 URL은 자동으로 바뀌지 않는다. URL 변경이
필요하면 기존 객체의 접근 가능 여부, DB 갱신 범위와 롤백 방법을 포함한 별도 이관 계획을 세운다.

## 관련 구현과 설정

- [`application.yml`](../src/main/resources/application.yml): 로컬 디렉터리, URL 접두어와 S3 환경 변수
- [`FileStorageDirectory`](../src/main/java/com/cakeshop/global/infra/FileStorageDirectory.java): 도메인별 최상위
  저장 경로
- [`AwsS3Config`](../src/main/java/com/cakeshop/global/config/AwsS3Config.java): AWS 자격 증명 공급자와 S3 Client
  구성
- [`S3StorageService`](../src/main/java/com/cakeshop/global/storage/S3StorageService.java): `s3` 프로필에서
  Spring이 선택하는 S3 저장·삭제 및 객체 키 생성용 `FileStorageClient` 내부 구현체. 도메인 Service에서
  직접 참조하거나 주입하지 않는다.
- [`LocalFileStorageClient`](../src/main/java/com/cakeshop/global/infra/LocalFileStorageClient.java): `s3`
  프로필이 비활성화됐을 때 Spring이 선택하는 로컬 디스크 저장·삭제용 `FileStorageClient` 내부 구현체.
  도메인 Service에서 직접 참조하거나 주입하지 않는다.
