<!-- OPENDOCK:START id=files:docs/rules/backend/API_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/API_RULES.md -->
# Backend API Rules

## 목표

백엔드 API 계약이 frontend, 외부 소비자, 운영 도구가 예측 가능한 형태로 유지되게 한다.

AI 에이전트는 이 문서를 기준으로 controller, request/response DTO, error response, pagination, 인증 헤더·쿠키, `.http` 산출물 변경을 판단한다.

## 적용 범위

- HTTP controller, request DTO, response DTO, error response, status code, cookie/session, CORS, `.http` 파일 변경에 적용한다.
- 공개 블로그 API, 관리자 CMS API, batch health API에 적용한다.
- API 보안은 [SECURITY_RULES.md](SECURITY_RULES.md), application 경계는 [ARCHITECTURE_RULES.md](ARCHITECTURE_RULES.md), 테스트는 [TESTING_RULES.md](TESTING_RULES.md)를 따른다.

## 정의

- API contract: method, path, request shape, response shape, status code, auth requirement, error format을 포함한 소비자와의 약속.
- Delivery DTO: HTTP request/response에만 사용하는 DTO.
- Error response: 실패를 소비자가 해석할 수 있게 만드는 machine-readable 응답.
- Public API: 로그인하지 않은 방문자 또는 공개 frontend가 호출할 수 있는 API.
- Admin API: 관리자 인증과 권한을 요구하는 CMS API.
- HTTP artifact: 실제 환경별 API 호출 예시를 담은 `backend/http/**/*.http` 파일.

## 규칙

- API-CTRL-001: Controller는 HTTP 요청을 application UseCase 호출로 변환하는 delivery adapter다.
- API-CTRL-002: Controller에 domain invariant, transaction orchestration, persistence access를 구현하지 않는다.
- API-CTRL-003: Controller는 로그만을 목적으로 `ApplicationException` 또는 `DomainException`을 catch 후 rethrow하지 않는다. HTTP 예외 응답 변환과 공통 API 실패 로그는 각 실행 앱의 GlobalExceptionHandler가 담당한다.
- API-DTO-001: request/response DTO는 API별로 소유한다.
- API-DTO-002: response DTO는 domain model이나 persistence entity를 직접 노출하지 않는다.
- API-DTO-003: application command/result와 API DTO는 mapper로 명시적으로 변환한다.
- API-PATH-001: path는 resource 중심으로 설계하고 action 이름 남용을 피한다.
- API-PATH-002: admin API와 public API의 path, auth, session context를 섞지 않는다.
- API-STATUS-001: 생성, 조회, 수정, 삭제, validation failure, auth failure, not found, conflict에 맞는 HTTP status를 사용한다.
- API-ERROR-001: error response는 code, message, 필요한 경우 field errors를 안정적으로 제공한다.
- API-ERROR-002: 내부 exception class 이름, stack trace, SQL, provider error raw body를 API response로 노출하지 않는다.
- API-PAGE-001: 목록 API는 offset 또는 cursor 방식 중 하나를 명시적으로 선택한다.
- API-PAGE-002: pagination response는 다음 페이지 판단에 필요한 값을 안정적으로 포함한다.
- API-AUTH-001: 인증이 필요한 API는 controller test 또는 security test로 인증 실패와 권한 실패를 검증한다.
- API-AUTH-002: public visitor session과 CMS admin session을 공유하지 않는다.
- API-CORS-001: CORS와 cookie 정책은 환경별 설정으로 관리한다.
- API-COMPAT-001: 기존 소비자가 의존하는 response field를 제거하거나 의미 변경할 때는 migration 영향을 명시한다.
- API-DOC-001: API endpoint, request DTO, response DTO, auth header, cookie, query parameter가 추가되거나 바뀌면 관련 `.http` 파일을 갱신한다.
- API-DOC-002: `.http` 파일은 `backend/http/{module}/{profile}-{domain}.http` 구조를 따른다.
- API-DOC-003: local과 prod 호출 예시가 모두 필요한 API는 두 profile 파일을 함께 확인한다.

## 우선순위

- API-PRIORITY-001: security rule은 API convenience보다 우선한다.
- API-PRIORITY-002: API 소비자 호환성은 내부 DTO 재사용 편의보다 우선한다.
- API-PRIORITY-003: API shape와 application port shape가 충돌하면 두 경계를 분리하고 mapper를 둔다.
- API-PRIORITY-004: API 계약 변경은 코드 변경만으로 완료하지 않고 HTTP artifact와 테스트를 함께 갱신한다.
<!-- OPENDOCK:END id=files:docs/rules/backend/API_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/API_RULES.md -->
