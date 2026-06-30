<!-- OPENDOCK:START id=files:docs/backend/api-specs/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/api-specs/README.md -->
# Backend API Specs

## 목표

`docs/backend/api-specs`는 백엔드 HTTP API 계약과 frontend 연동 계약 산출물을 보관한다.

## 적용 범위

- request, response, error, pagination, authentication, frontend integration contract를 장기 보존해야 하는 API 문서에 적용한다.
- 실제 API 규칙 원문은 `docs/rules/backend/API_RULES.md`가 소유한다.

## 규칙

- BACKEND-API-SPEC-001: API spec은 API 계약 산출물이며 백엔드 규칙 Source of Truth가 아니다.
- BACKEND-API-SPEC-002: API spec은 `docs/rules/backend/API_RULES.md`와 충돌하면 안 된다.
- BACKEND-API-SPEC-003: API 변경 시 관련 `.http`, 테스트, frontend 계약 영향 여부를 함께 기록한다.
<!-- OPENDOCK:END id=files:docs/backend/api-specs/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/api-specs/README.md -->
