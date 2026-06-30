<!-- OPENDOCK:START id=files:docs/rules/backend/TESTING_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/TESTING_RULES.md -->
# Backend Testing Rules

## 목표

백엔드 변경이 기능 동작, 아키텍처 경계, 보안 정책, 데이터 정합성을 반복 가능하게 검증하도록 한다.

AI 에이전트는 이 문서를 기준으로 어떤 테스트를 추가하거나 수정해야 하는지, 어떤 검증 명령을 fresh run 해야 하는지 판단한다.

## 적용 범위

- `backend/modules/**/src/test/**` 테스트 코드와 test fixture 변경에 적용한다.
- domain, application, controller, adapter, security, package structure, profile configuration 테스트에 적용한다.
- 테스트 실행 명령과 build/lint 검증에 적용한다.

## 정의

- Unit test: 외부 런타임 없이 단일 책임 단위를 검증하는 테스트.
- Slice test: MVC, JPA, security 같은 특정 Spring slice를 제한적으로 검증하는 테스트.
- Integration test: DB, Spring context, profile, adapter wiring 같은 런타임 통합을 검증하는 테스트.
- Contract test: API, port, security, error response처럼 소비자가 의존하는 계약을 검증하는 테스트.
- Architecture test: package, dependency, module boundary 규칙을 검증하는 테스트.
- Fresh run: 변경 이후 같은 작업 턴에서 새로 실행한 검증 명령.

## 규칙

- TEST-SCOPE-001: domain invariant는 domain unit test로 검증한다.
- TEST-SCOPE-002: application orchestration은 application service 또는 UseCase test로 검증한다.
- TEST-SCOPE-003: controller는 request/response, status, validation, security boundary를 검증한다.
- TEST-SCOPE-004: persistence adapter는 query, mapping, transaction, migration 영향이 있으면 integration 수준에서 검증한다.
- TEST-SCOPE-005: external adapter는 provider success, provider failure, timeout/error mapping을 테스트 대역으로 검증한다.
- TEST-SCOPE-006: security 변경은 인증 성공, 인증 실패, 권한 실패, session/cookie 경계를 모두 검증한다.
- TEST-ARCH-001: 모듈 또는 패키지 경계를 바꾸면 architecture/package structure test를 추가하거나 갱신한다.
- TEST-ARCH-002: architecture rule 위반을 테스트로 표현할 수 있으면 문서만 믿지 않고 테스트로 고정한다.
- TEST-DATA-001: DB/schema 변경은 migration 적용 가능성과 repository/adapter 동작을 검증한다.
- TEST-DATA-002: pagination, cursor, sorting, filtering은 boundary case를 포함한다.
- TEST-API-001: API 변경은 성공 응답뿐 아니라 validation error, auth error, not found, conflict 같은 실패 응답을 검증한다.
- TEST-FIXTURE-001: fixture는 테스트 의도를 드러내는 이름과 최소 필드만 가진다.
- TEST-FIXTURE-002: 여러 테스트가 공유하는 fixture가 의미를 숨기면 테스트별 fixture를 우선한다.
- TEST-MOCK-001: domain model과 value object는 불필요하게 mocking하지 않는다.
- TEST-MOCK-002: 외부 API, clock, id generator, password encoder, storage, message broker는 테스트 대역을 사용할 수 있다.
- TEST-MOCK-003: 과도한 mocking으로 실제 orchestration을 검증하지 못하면 integration 또는 slice test를 선택한다.
- TEST-FLAKY-001: sleep, real time dependency, 테스트 순서 의존, 외부 네트워크 의존을 새 테스트에 도입하지 않는다.
- TEST-NAME-001: 테스트 이름은 조건, 행위, 기대 결과를 드러낸다.
- TEST-VERIFY-001: backend 작업 완료 전 관련 Gradle test를 fresh run 한다.
- TEST-VERIFY-002: formatting 또는 style 영향이 있으면 ktlint 검증을 fresh run 한다.
- TEST-VERIFY-003: 실행 앱 packaging, runtime configuration, dependency 변경이 있으면 bootJar 또는 해당 build 검증을 fresh run 한다.

## 우선순위

- TEST-PRIORITY-001: 보안, 데이터 정합성, API 계약 실패 테스트는 단순 happy path 테스트보다 우선한다.
- TEST-PRIORITY-002: architecture boundary 변경은 기능 테스트만으로 완료하지 않는다.
- TEST-PRIORITY-003: 빠른 구현을 이유로 flaky test나 외부 네트워크 의존 테스트를 추가하지 않는다.
- TEST-PRIORITY-004: 검증을 실행할 수 없으면 완료로 보고하지 말고 실행하지 못한 명령과 이유를 보고한다.
<!-- OPENDOCK:END id=files:docs/rules/backend/TESTING_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/TESTING_RULES.md -->
