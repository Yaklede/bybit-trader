<!-- OPENDOCK:START id=files:docs/backend/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/README.md -->
# Backend Artifacts

## 목표

`docs/backend`는 백엔드 TDD, API spec, ADR, 참고 자료 같은 백엔드 산출물의 홈이다.

현재 유효한 백엔드 규칙 Source of Truth는 [docs/rules/backend](../rules/backend/README.md)가 소유한다.

## 문서 맵

| 영역 | 경로 | 책임 |
|------|------|------|
| Backend rules | [../rules/backend/README.md](../rules/backend/README.md) | 백엔드 작업에서 반드시 따라야 하는 규칙 |
| TDD | [tdd/README.md](tdd/README.md) | 기능 또는 변경 단위 기술 설계 산출물 |
| API specs | [api-specs/README.md](api-specs/README.md) | HTTP API 계약과 frontend 연동 계약 |
| ADR | [adr/README.md](adr/README.md) | 장기 보존할 백엔드 아키텍처 의사결정 |
| Volume-flow aggressive risks | [volume-flow-aggressive-risk-register.md](volume-flow-aggressive-risk-register.md) | 공격형 전략 운영 전 후속 관리할 리스크 목록 |
| Volume-flow production readiness | [volume-flow-production-readiness-plan.md](volume-flow-production-readiness-plan.md) | 공격형 전략을 온프레미스 운영 봇으로 전환하기 위한 마일스톤 |

## 규칙

- BACKEND-ARTIFACT-001: `docs/backend` 하위 문서는 백엔드 산출물과 참고 자료만 소유한다.
- BACKEND-ARTIFACT-002: 백엔드 규칙 원문은 `docs/rules/backend`에 둔다.
- BACKEND-ARTIFACT-003: TDD, API spec, ADR은 필요한 경우 `docs/rules/backend` 규칙을 Source of Truth로 참조한다.
- BACKEND-ARTIFACT-004: `docs/backend` 산출물은 `docs/rules/backend` 규칙과 충돌하면 안 된다.
<!-- OPENDOCK:END id=files:docs/backend/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/README.md -->
