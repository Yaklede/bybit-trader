---
name: backend-tech-design-write
description: backend 기능 개발이나 시스템 유지보수에서 기술설계문서(TDD)가 필요할 때 적용된다. 이 스킬은 UseCase, 도메인 모델, app/application/storage/external 계층, DB/schema, 트랜잭션, 정합성, 예외·실패 처리, 동시성, backend 설계 리뷰 자료를 실제 구현 가능 수준으로 설계한다.
---

# backend-tech-design-write — 백엔드 기술설계문서 작성

## 목표

기술설계문서(TDD)는 "무엇을 만든다"보다 **"왜 이렇게 설계했는가"**에 초점을 맞춘 엔지니어링 문서다.
실행 계획서가 작업 순서와 범위를 다룬다면, TDD는 아키텍처 판단의 근거, 계층 간 책임 분배, 트랜잭션 경계, 실패 시나리오 대응, 동시성 제어, 확장성 설계를 다룬다.

이 스킬은 backend 신규 기능, 기존 기능 변경, 리팩토링, DB/schema 변경, 트랜잭션·정합성·실패 처리 설계가 필요한 작업에서 구현자와 reviewer가 공유할 수 있는 설계 근거 문서를 만든다. 이 문서를 읽는 사람이 "이 코드가 왜 이렇게 생겼는지"를 이해할 수 있어야 한다.

## 작업 절차

1. 사용자 요청에서 설계 대상, 포함 범위, 제외 범위를 한 문장으로 고정한다.
2. 범위가 모호하면 구현이나 문서 작성 전에 질문한다.
3. [references/README.md](references/README.md)에서 참조 문서 역할을 확인한다.
4. [backend-tdd-workflow.md](references/backend-tdd-workflow.md)에 따라 `docs/rules/backend` active RULES 문서와 관련 코드를 선별해 확인한다.
5. 확인한 근거를 바탕으로 아키텍처, 계층 책임, 도메인 모델, 트랜잭션, 정합성, 실패 처리, 동시성, 성능, 확장성 판단을 도출한다.
6. [backend-tdd-template.md](references/backend-tdd-template.md)의 섹션 구조를 기준으로 TDD를 작성하거나 기존 TDD를 갱신한다.
7. TDD는 active `docs/rules/backend`가 아니라 `.agents/runs/{run_id}` 하위 output artifact 또는 사용자가 명시한 output artifact 경로에 저장한다.
8. 작성한 TDD를 품질 검증 기준에 맞춰 확인한 뒤 결과를 보고한다.

## 결과물

작업이 끝나면 변경 범위에 맞게 아래 산출물 또는 결과 상태가 존재해야 한다.

- `.agents/runs/{run_id}/outputs/M*/tdd-{feature-slug}.md` 또는 사용자가 명시한 output artifact: 새로 작성한 backend TDD
- 기존 run artifact TDD 갱신본: 관련 설계 문서가 이미 있을 때
- 설계 근거 요약: 확인한 `docs/rules/backend/**` 문서와 backend 코드 근거
- 주요 설계 판단: 아키텍처, 도메인 모델, 트랜잭션, 실패 처리, 동시성, 성능, 확장성 결정과 채택 이유
- 검증 결과: 구조 점검, 문서 맵 확인, 남은 확인 필요 사항
- 완료 보고: TDD 경로, 설계 범위, 주요 설계 판단, 남은 확인 필요 사항

## 품질 검증 기준

- 모든 내용은 한국어로 작성한다.
- 코드 예시, 파일 경로, SQL, 클래스명은 원문 그대로 유지한다.
- TDD가 설계 배경, 현행 분석, 아키텍처, 도메인 모델, 트랜잭션, 실패 처리, 동시성, 검증 계획을 필요한 수준으로 다룬다.
- 각 주요 설계 결정에는 "왜 이 선택을 했는가"와 확인한 근거가 함께 있다.
- 계층 책임, 트랜잭션 경계, 실패 처리, 동시성 제어가 단순 나열이 아니라 판단 근거를 포함한다.
- 설계 판단이 `docs/rules/backend/**`와 실제 backend 코드에서 확인한 사실과 충돌하지 않는다.
- 저장소 문서와 코드에서 확인되지 않은 프로젝트 규칙을 임의로 추가하지 않는다.
- 단일 CRUD 또는 단순 UseCase처럼 별도 설계 판단이 거의 없는 작업은 TDD 작성 필요성을 먼저 재검토한다.
- frontend 상태/API/component/routing 설계는 이 스킬의 결과물로 작성하지 않는다.
- 해당 없는 섹션은 빈 상태로 두지 않고 생략하거나 `해당 없음`으로 명시한다.
- 새 TDD를 만든 경우 active `docs/rules/backend`에 영구 설계 문서로 추가하지 않았다.
- 아래 스킬 구조 검증이 통과한다.

```bash
python3 .agents/skills/skill-artifact-write/scripts/check_skill_artifact.py .agents/skills/backend-tech-design-write/SKILL.md
```
