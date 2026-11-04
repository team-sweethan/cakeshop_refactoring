# Thymeleaf 화면 작성 규칙

이 문서는 새 고객·관리자 화면을 만들 때 공통 레이아웃과 프래그먼트를 일관되게 사용하는 방법을 정한다.
Spring이나 Thymeleaf가 실행 중에 읽는 설정 파일은 아니며, 실제 렌더링 계약의 정본은 템플릿과
프래그먼트 코드다. API, Service, DB 연동은 이 문서의 범위에 포함하지 않는다.
화면의 키보드·포커스·상태 메시지와 시각적 접근성 기준은
[`web-accessibility.md`](web-accessibility.md)를 함께 따른다.

## 1. 렌더링 흐름

1. Controller가 View 이름과 화면에 필요한 Model을 반환한다.
2. Spring MVC가 `src/main/resources/templates`에서 Thymeleaf 템플릿을 찾는다.
3. Thymeleaf가 `th:replace` 프래그먼트와 Model 표현식을 처리한다.
4. 브라우저가 `src/main/resources/static`의 CSS·JavaScript를 요청한다.

이 문서를 수정하거나 삭제해도 렌더링 결과는 직접 바뀌지 않는다. 화면 동작을 바꾸려면 Controller,
템플릿, 프래그먼트 또는 정적 자원을 수정해야 한다.

## 2. 템플릿 배치

- 고객 기능 화면: `templates/customer/<도메인>/`
- 관리자 기능 화면: `templates/admin/<도메인>/`
- 로그인·홈·오류처럼 역할별 기능 화면이 아닌 페이지: `templates/auth`, `templates/home`,
  `templates/error`
- 둘 이상의 화면이 실제로 공유하는 조각: `templates/fragments/{common,customer,admin}`

화면 전용 마크업은 각 템플릿에 두고, 여러 화면에서 같은 구조와 동작을 반복할 때만 프래그먼트로
분리한다. 향후 재사용 가능성만으로 빈 프래그먼트를 미리 만들지 않는다.

## 3. 고객 화면 골격

고객 기능 화면은 공통 header와 `page-container`를 사용한다. footer는 화면에 필요한 경우 사용하며,
`store`가 없으면 `null`을 전달해 매장 정보 준비 문구를 표시할 수 있다.

```html
<!doctype html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>화면명 | 스윗한 케이크</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
  <!-- 필요한 기능별 CSS -->
  <script defer th:src="@{/js/app.js}"></script>
  <!-- 필요한 기능별 JavaScript -->
</head>
<body>
  <header th:replace="~{fragments/common/header :: header}"></header>
  <main class="page-container">
    <!-- 화면 고유 내용 -->
  </main>
  <footer th:replace="~{fragments/common/footer :: footer(${store})}"></footer>
</body>
</html>
```

고객 메뉴는 `fragments/common/header.html`이 불러오는 `fragments/customer/gnb.html`에서 관리한다.

## 4. 관리자 화면 골격

관리자 기능 화면은 sidebar, header, `admin-layout`, `admin-content` 구조를 사용한다.

```html
<!doctype html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>화면명 | 관리자</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
  <!-- 필요한 기능별 CSS -->
</head>
<body>
  <div class="admin-layout">
    <aside th:replace="~{fragments/admin/sidebar :: sidebar('activeMenu')}"></aside>
    <div class="admin-main">
      <header th:replace="~{fragments/admin/header :: header('화면명')}"></header>
      <main class="admin-content">
        <!-- 화면 고유 내용 -->
      </main>
    </div>
  </div>
</body>
</html>
```

`activeMenu` 값은 `fragments/admin/sidebar.html`에 정의된 메뉴 키를 사용한다. 메뉴 키 목록을 이 문서에
복사하지 않고 sidebar를 정본으로 삼는다.

## 5. 공통 프래그먼트 계약

| 프래그먼트 | 인자 | 역할 |
|---|---|---|
| `fragments/common/header :: header` | 없음 | 고객 header와 고객 GNB |
| `fragments/common/footer :: footer` | `store` | 매장 정보 footer. `null`이면 준비 문구 사용 |
| `fragments/common/alert :: alert` | 없음 | `successMessage`, `errorMessage` 출력 |
| `fragments/admin/sidebar :: sidebar` | `activeMenu` | 관리자 메뉴와 현재 메뉴 표시 |
| `fragments/admin/header :: header` | `title` | 관리자 화면 제목과 사용자 동작 |
| `fragments/common/head :: head` | `title` | `<title>`과 `app.css`·`app.js` |

`fragments/common/head :: head`는 단순 화면에서 선택적으로 사용할 수 있고 **제목을 인자로 받는다**(`head('쿠폰 상세 정보 | 관리자')`). 이 프래그먼트는
`app.css`와 `app.js`까지 포함하므로 기능별 자원을 추가해야 하거나 같은 자원이 다른 프래그먼트에서
이미 로드되는 화면에서는 중복 로드를 확인한다.

프래그먼트 인자나 Model 키를 변경하면 이를 사용하는 모든 템플릿과 대표 렌더링 테스트를 함께 확인한다.

## 6. CSS와 JavaScript

- 전체 화면이 공유하는 토큰과 기본 컴포넌트는 `static/css/app.css`에 둔다.
- 특정 도메인이나 여러 관련 화면이 공유하는 스타일·동작은 역할이 드러나는 기능별 파일로 분리한다.
- 한 화면만을 위한 로직을 `app.css`나 `app.js`에 넣지 않는다.
- 같은 CSS·JavaScript 파일을 한 응답에서 두 번 불러오지 않는다.
- 공통 클래스와 관리자 메뉴의 전체 목록을 문서에 복사하지 않고 `app.css`와 sidebar를 확인한다.
- 정적 자원 경로는 `th:href`, `th:src`로 작성한다.

## 7. Thymeleaf와 보안

- 링크와 폼 URL은 `th:href`, `th:action`으로 만든다.
- POST·PUT·PATCH·DELETE 동작은 Spring Security의 CSRF 정책을 적용한다.
- `sec:authorize`와 조건부 버튼 노출은 사용자 편의를 위한 표현이며 서버 인가를 대신하지 않는다.
- 템플릿은 DB 구조를 탐색하거나 상태 전이와 권한 같은 업무 규칙을 계산하지 않는다.
- Entity 대신 화면에 필요한 View DTO와 선택 목록을 Model로 받는다.
- 렌더링 결과에 남으면 안 되는 설명은 일반 HTML 주석 대신 Thymeleaf parser-level 주석
  `<!--/* ... */-->`을 사용한다.

## 8. 검증

- Controller 테스트는 View 이름과 필요한 Model 연결을 확인한다.
- 실제 템플릿 연결은 대표 렌더링 smoke test로 확인한다.
- 비어 있음, 한 건, 여러 건을 모두 반복하지 않고 결과가 실질적으로 달라지는 상태만 선택한다.
- 버튼 숨김 테스트는 Security 인가 테스트를 대신하지 않는다.
- 상세 테스트 기준은 [testing.md](testing.md)의 Thymeleaf 화면 절을 따른다.
