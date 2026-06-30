<!-- OPENDOCK:START id=files:docs/rules/backend/README.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/README.md -->
# Backend Rules

## 목표

`docs/rules/backend`는 AI 에이전트가 백엔드 작업을 수행할 때 항상 따라야 하는 현재 유효한 Source of Truth다.

이 디렉토리는 작업 절차, 체크리스트, 튜토리얼, 참고 자료를 보관하지 않는다. 신규 개발, 리팩토링, 레거시 개선, 대규모 코드베이스 확장 상황에서 일관된 아키텍처 판단과 코딩 품질을 강제하는 규칙 문서만 둔다.

## 적용 범위

- `backend-implement`를 통해 수행하는 백엔드 기능 구현, 리팩토링, 설계, 리뷰, 검증 작업에 적용한다.
- `backend/**` 코드, 빌드 설정, HTTP 계약, DB/schema, 인증/인가, 운영 설정, 테스트 변경에 적용한다.
- 이 디렉토리 바로 아래의 문서만 active backend rules Source of Truth로 본다.
- `docs/backend/**`, `docs/_archive/**`, `.agents/runs/**`, 과거 TDD, 과거 상세 전략 문서는 참고 자료 또는 산출물일 뿐 현재 규칙으로 사용하지 않는다.

## 정의

- Source of Truth: AI 에이전트가 설계, 구현, 리뷰 판단에서 반드시 신뢰해야 하는 현재 유효한 기준.
- RULES 문서: 설명이나 가이드가 아니라 위반 여부를 판단할 수 있는 규칙 문서.
- Active rules: `docs/rules/backend` 바로 아래에 있는 현재 규칙 문서.
- Archived docs: 보존 가치는 있지만 현재 작업의 강제 기준으로 사용하지 않는 과거 문서.

## 규칙

- BACKEND-DOC-001: 백엔드 작업의 active rules Source of Truth는 `docs/rules/backend` 바로 아래 문서들이다.
- BACKEND-DOC-002: `docs/rules/backend` 아래에는 현재 유효한 backend 규칙 문서만 둔다.
- BACKEND-DOC-003: TDD, API spec, 상세 구현 전략, 코드 역공학 기록, 참고 문서는 `docs/rules/backend` 밖의 산출물 디렉토리 또는 archive로 분리한다.
- BACKEND-DOC-004: 규칙 문서는 `목표`, `적용 범위`, `정의`, `규칙`, `우선순위` 섹션을 가진다.
- BACKEND-DOC-005: 규칙은 reviewer가 인용할 수 있도록 안정적인 rule id를 가진다.
- BACKEND-DOC-006: 새 규칙 문서를 추가하거나 제거하면 이 README의 문서 맵을 함께 갱신한다.
- BACKEND-DOC-007: 규칙이 특정 구현 예시에만 의존하면 안 된다. 예시는 규칙을 설명할 수 있지만 규칙 자체가 되어서는 안 된다.
- BACKEND-DOC-008: 기존 코드 관례가 RULES 문서와 충돌하면 RULES 문서를 우선하고, 필요한 경우 사용자에게 레거시 예외 여부를 확인한다.

문서 맵:

| 문서 | 책임 |
|------|------|
| [PACKAGE_STRUCTURE.md](PACKAGE_STRUCTURE.md) | 패키지, 파일, command/query 배치 규칙 |
| [ARCHITECTURE_RULES.md](ARCHITECTURE_RULES.md) | 모듈 책임, 레이어 책임, 의존 방향, CQRS, port/adapter, 트랜잭션 경계 규칙 |
| [CODING_RULES.md](CODING_RULES.md) | naming, DTO, mapper, exception, validation, Kotlin 작성 규칙 |
| [TESTING_RULES.md](TESTING_RULES.md) | unit, slice, integration, security, package structure 검증 규칙 |
| [API_RULES.md](API_RULES.md) | HTTP API 계약, request/response, error, pagination, `.http` 산출물 규칙 |
| [DATA_RULES.md](DATA_RULES.md) | DB schema, migration, transaction, query, persistence adapter 규칙 |
| [SECURITY_RULES.md](SECURITY_RULES.md) | 인증, 인가, 세션, 비밀값, 민감정보 보호 규칙 |
| [OPERATIONS_RULES.md](OPERATIONS_RULES.md) | profile, config, logging, health, deploy, 운영 안전성 규칙 |

작업별 우선 확인 문서:

| 작업 유형 | 우선 확인 |
|-----------|-----------|
| 패키지, 파일, command/query 배치 | `PACKAGE_STRUCTURE.md`, `ARCHITECTURE_RULES.md` |
| 모듈 책임, UseCase, domain model, port/adapter 변경 | `ARCHITECTURE_RULES.md`, `CODING_RULES.md`, `TESTING_RULES.md` |
| Controller, HTTP API, frontend 계약 변경 | `API_RULES.md`, `SECURITY_RULES.md`, `TESTING_RULES.md` |
| DB/schema, repository, query 변경 | `DATA_RULES.md`, `ARCHITECTURE_RULES.md`, `TESTING_RULES.md` |
| 인증, 인가, 세션, 비밀값 변경 | `SECURITY_RULES.md`, `API_RULES.md`, `OPERATIONS_RULES.md` |
| logging, profile, deploy, health 변경 | `OPERATIONS_RULES.md`, `SECURITY_RULES.md` |

## 우선순위

- BACKEND-PRIORITY-001: 보안과 데이터 정합성 규칙은 편의성, 기존 관례, 빠른 구현보다 우선한다.
- BACKEND-PRIORITY-002: 문서 간 충돌이 있으면 `SECURITY_RULES.md`와 `DATA_RULES.md`를 먼저 적용하고, 그 다음 `ARCHITECTURE_RULES.md`, `PACKAGE_STRUCTURE.md`, `API_RULES.md`, `CODING_RULES.md`, `TESTING_RULES.md`, `OPERATIONS_RULES.md` 순서로 판단한다.
- BACKEND-PRIORITY-003: 사용자 명시 요구사항이 RULES 문서와 충돌하면 구현 전에 충돌을 보고하고 확인을 받는다.
- BACKEND-PRIORITY-004: 기존 코드가 RULES 문서를 위반하면 새 코드에 위반을 복제하지 않는다.
- BACKEND-PRIORITY-005: 규칙이 없는 레거시 영역은 기존 관례를 따를 수 있지만, 새 규칙이 생기면 새 규칙을 우선한다.
<!-- OPENDOCK:END id=files:docs/rules/backend/README.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/README.md -->
