<!-- OPENDOCK:START id=files:docs/backend/tdd/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/tdd/README.md -->
# Backend TDD

## 목표

`docs/backend/tdd`는 장기 보존할 백엔드 기술설계문서(TDD)를 보관한다.

## 적용 범위

- 기능 개발, 리팩토링, 데이터 변경, 외부 연동 변경처럼 설계 판단을 장기 보존해야 하는 백엔드 작업에 적용한다.
- 일회성 실행 산출물은 기본적으로 `.agents/runs/{run_id}` 하위에 두고, 장기 참조 가치가 있는 문서만 이 디렉토리로 승격한다.

## 규칙

- BACKEND-TDD-001: TDD는 현재 유효한 규칙 Source of Truth가 아니라 설계 판단 산출물이다.
- BACKEND-TDD-002: TDD가 참조하는 백엔드 규칙은 `docs/rules/backend` 경로를 사용한다.
- BACKEND-TDD-003: 반복 적용해야 하는 판단은 TDD에만 남기지 않고 사용자 확인 후 `docs/rules/backend` 규칙으로 승격한다.
<!-- OPENDOCK:END id=files:docs/backend/tdd/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/tdd/README.md -->
