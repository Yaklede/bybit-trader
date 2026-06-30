---
name: backend-implement
description: 사용자가 $backend-implement를 명시적으로 호출하고 백엔드 기능 구현, 리팩토링, UseCase 추가, 도메인 모델 변경, storage/external/app/application 계층 변경, DB/schema 변경, 또는 backend 아키텍처 기준 영향 작업을 요청할 때 적용된다.
---

# backend-implement

## 목표

백엔드 작업을 요구사항 확정 → 기술설계문서(TDD) 작성 → 코드 구현 → 코드 리뷰 → 검증 보고 순서로 표준화해, 누가 언제 수행하더라도 `docs/rules/backend/**` 기준을 따르는 일관된 결과물을 만든다.

이 스킬의 핵심은 직접 구현이 아니라 오케스트레이션이다. 메인 에이전트는 질문, 범위 고정, Source of Truth 패키지 고정, 서브에이전트 호출, 반복 제어, 결과 검증, 사용자 보고를 맡는다. 메인 에이전트는 일반 경로에서 backend 코드를 직접 고치지 않는다.

## 작업 절차

1. 요구사항 명확화
   - 목표, 범위, 제외사항, 성공 기준, 외부 계약, DB/schema 영향, 보안/운영 정책을 확인한다.
   - `.agents/runs/{run_id}/inputs/requirement-context.md`를 [references/run-artifacts.md](references/run-artifacts.md)의 Requirement Context 템플릿으로 작성한다.
   - Requirement Context 템플릿의 섹션명, 순서, 언어, 표 구조를 바꾸지 않는다.
   - 기능명, 문서 정보, 개요, 용어, 사용자 시나리오, 비즈니스 규칙, 상태 변경, 데이터 영향도, 이벤트, 예외 처리, 운영 고려사항, 제약사항 중 조금이라도 모호하거나 비어 있으면 2단계로 넘어가지 않는다.
   - 모호한 항목은 사용자와 질문을 주고받아 확정하고, 확정된 내용으로 `requirement-context.md`를 완성한 뒤 Source of Truth 패키지 고정으로 넘어간다.

2. Source of Truth 패키지 고정
   - 항상 `docs/rules/backend/README.md`를 포함한 `docs/rules/backend/**` 전체를 backend 공통 Source of Truth로 고정한다.
   - 작업 성격에 따라 일부 문서만 선별하지 않는다.
   - 작업별 Source of Truth로 사용자 요청, `requirement-context.md`, TDD, 명시 결정, 수정 루프의 reviewer output을 함께 고정한다.
   - role input에는 개별 선정 목록 대신 `docs/rules/backend/**` 기준 경로와 "backend 기준 전체 준수"라는 고정 사유를 남기고, 작업별 Source of Truth 경로를 별도 섹션으로 남긴다.
   - 작업별 결정은 기능 범위와 동작을 구체화하지만 backend 공통 Source of Truth를 우회하거나 약화할 수 없다.
   - backend 공통 Source of Truth와 작업별 Source of Truth가 충돌하면 구현·리뷰로 넘어가지 않고 사용자에게 규칙 정리 또는 작업 결정 수정을 요청한다.
   - 문서가 없거나 실제 코드와 맞지 않으면 먼저 사용자에게 문서 정리 필요성을 알리거나 `backend-rules-derive-from-code` 사용을 제안한다.

3. run artifact 준비
   - `.agents/runs/{run_id}` 아래에 요구사항, role input/output, checkpoint 파일을 만든다.
   - 자세한 최소 형식은 [references/run-artifacts.md](references/run-artifacts.md)를 따른다.

4. TDD 작성 서브에이전트 호출
   - `backend-technical-design-writer`를 호출한다.
   - 입력에는 확정 요구사항, 제외사항, Source of Truth 패키지, TDD 저장 경로, 출력 파일, 체크포인트 파일을 포함한다.
   - 결과는 구현자가 따를 수 있는 backend TDD 또는 TDD 생략 불가/차단 사유여야 한다.

5. 코드 구현 서브에이전트 호출
   - `backend-implementation-engineer`를 호출한다.
   - 입력에는 TDD 결과, Source of Truth 패키지, 변경 범위, 검증 명령을 포함한다.
   - 구현자는 `docs/rules/backend` 하위 규칙 문서와 작업별 Source of Truth를 기준으로 코드를 수정하고 검증 결과를 남긴다.
   - 구현자는 Source of Truth 패키지가 누락되었거나 충돌하면 임의 보완하지 않고 `needs_user_answer` 또는 `failed` 상태로 멈춘다.

6. 코드 리뷰 서브에이전트 호출
   - `backend-architecture-reviewer`를 호출한다.
   - 입력에는 구현 결과, 변경 파일, TDD, Source of Truth 패키지를 포함한다.
   - reviewer는 `docs/rules/backend` 하위 규칙 문서와 작업별 Source of Truth 기준 위반만 판정한다.
   - reviewer는 Source of Truth 패키지가 누락되었거나 충돌하면 임의 기준으로 pass 또는 violation을 만들지 않고 근거 부족을 출력한다.
   - reviewer는 독립적인 review output artifact에 판정과 근거를 남긴다.

7. 최대 5회 수정 루프
   - reviewer가 `blocker` 또는 `major` 위반을 보고하면 같은 구현 서브에이전트에 수정 입력을 보낸다.
   - 수정 후 reviewer를 다시 호출한다.
   - 구현과 리뷰 반복은 최대 5회다.
   - 5회 후에도 위반이 남으면 직접 고치지 말고 위반 요약, 시도한 수정, 남은 결정 사항을 사용자에게 질문한다.

8. 완료 보고
   - 변경 파일, TDD 경로, 테스트/lint/build 결과, reviewer 판정, 남은 위험 또는 사용자 결정 사항을 짧게 보고한다.

## 결과물

- `.agents/runs/{run_id}/inputs/requirement-context.md`: 기능명, 문서 정보, 개요, 용어, 사용자 시나리오, 비즈니스 규칙, 상태 변경, 데이터 영향도, 이벤트, 예외 처리, 운영 고려사항, 제약사항이 사용자 확인을 거쳐 완성된 요구사항 컨텍스트
- `.agents/runs/{run_id}/inputs/M*/`: TDD writer, implementation engineer, architecture reviewer에게 전달한 role input
- `.agents/runs/{run_id}/outputs/M*/`: 각 서브에이전트의 role output
- `.agents/runs/{run_id}/checkpoints/M*/`: 단계별 진행 상태와 다음 호출이 이어받을 주의사항
- `.agents/runs/{run_id}/outputs/M*/` 하위 output artifact: 구현자가 따를 수 있는 backend TDD
- backend 코드 변경과 검증 결과
- reviewer 판정과 남은 위험 또는 사용자 결정 사항
- 사용자에게 전달할 완료 보고

## 품질 검증 기준

- Source of Truth 패키지는 항상 `docs/rules/backend/README.md`를 포함한 `docs/rules/backend/**` 전체를 backend 공통 Source of Truth로 고정한다.
- Source of Truth 패키지는 사용자 요청, `requirement-context.md`, TDD, 명시 결정, reviewer output 같은 작업별 Source of Truth를 함께 포함한다.
- 요구사항 명확화 단계에서 `requirement-context.md`를 [references/run-artifacts.md](references/run-artifacts.md)의 Requirement Context 템플릿으로 작성했다.
- Requirement Context 템플릿의 섹션명, 순서, 언어, 표 구조를 보존했다.
- Requirement Context 템플릿에 모호하거나 비어 있는 항목이 있으면 2단계로 넘어가기 전에 사용자와 질문을 주고받아 확정했다.
- role input에는 backend 공통 Source of Truth로 `docs/rules/backend/**` 기준 경로와 "backend 기준 전체 준수"라는 고정 사유를 남기고, 작업별 Source of Truth 경로를 별도 섹션으로 남긴다.
- backend 공통 Source of Truth와 작업별 Source of Truth가 충돌하면 구현·리뷰를 진행하지 않고 사용자 결정 또는 규칙 정리를 요청했다.
- 문서가 없거나 실제 코드와 맞지 않으면 먼저 사용자에게 문서 정리 필요성을 알리거나 `backend-rules-derive-from-code` 사용을 제안한다.
- 세부 호출 프롬프트와 출력 형식은 [references/subagent-workflow.md](references/subagent-workflow.md)를 따른다.
- 서브에이전트는 서로 직접 호출하지 않는다.
- 서브에이전트는 전달받은 Source of Truth 패키지와 input artifact만 신뢰한다.
- reviewer는 취향, 일반론, 입력에 없는 규칙을 violation으로 만들지 않는다.
- reviewer `pass`는 `backend-architecture-reviewer`의 독립 판정이거나 사용자 승인 하에 기록된 fallback review 판정이어야 한다.
- 구현자는 reviewer의 `blocker`/`major` 위반을 해결하는 범위로만 수정한다.
- 서브에이전트 도구가 없거나 정책상 호출이 막히면 구현 전에 사용자에게 fallback 진행 여부를 확인하고, 승인 없는 in-process 자체 검토를 reviewer `pass`로 확정하지 않는다.

스킬 또는 관련 artifact 구조를 바꾼 뒤 실행한다.

```bash
python3 .agents/skills/skill-artifact-write/scripts/check_skill_artifact.py .agents/skills/backend-implement/SKILL.md
python3 .agents/scripts/validate-context-checkpoints.py
python3 .agents/scripts/check-playbook.py
```

백엔드 작업 자체를 완료할 때는 해당 작업의 Gradle test, ktlint, bootJar 등 실제 검증 명령을 fresh run으로 수행한다.
