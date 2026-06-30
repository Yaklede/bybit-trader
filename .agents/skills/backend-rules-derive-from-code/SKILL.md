---
name: backend-rules-derive-from-code
description: 현재 backend 코드베이스를 분석해 docs/rules/backend 하위 active RULES 문서를 처음 생성하거나 현재 코드 기준으로 전면 재작성해야 할 때 적용된다.
---

# Backend Rules From Code

## 목표

현재 경로의 백엔드 코드베이스를 분석해 프로젝트에 종속된 `docs/rules/backend` active RULES 지식 시스템을 생성한다.

이 스킬은 `.agents`, `.codex`, `docs` 문서 체계를 새 프로젝트나 기존 프로젝트에 가져온 뒤, 그 프로젝트의 실제 backend 코드가 따르는 구조와 반복 패턴을 backend 규칙 Source of Truth로 세우기 위한 스킬이다. 기존 규칙 문서를 권위 있는 원천으로 보존하는 작업이 아니라, 현재 코드 근거와 사용자 확인을 기준으로 target 문서를 전면 재작성한다.

포함 범위:

- `docs/rules/backend/README.md`
- `docs/rules/backend/PACKAGE_STRUCTURE.md`
- `docs/rules/backend/ARCHITECTURE_RULES.md`
- `docs/rules/backend/CODING_RULES.md`
- `docs/rules/backend/TESTING_RULES.md`
- `docs/rules/backend/API_RULES.md`
- `docs/rules/backend/DATA_RULES.md`
- `docs/rules/backend/SECURITY_RULES.md`
- `docs/rules/backend/OPERATIONS_RULES.md`

제외 범위:

- frontend 문서
- PRD
- 구현 코드 변경
- `docs/backend/**` 산출물 문서
- 코드 근거 또는 사용자 확인 없이 만드는 새 규칙
- 기존 문서 문구를 현재 코드 근거보다 우선하는 작업
- 과거 TDD나 상세 설계 문서를 active `docs/rules/backend` Source of Truth로 복원하는 작업

최상위 backend 규칙 또는 산출물 홈 경로가 바뀔 때만 `AGENTS.md` 문서 맵을 갱신한다.

## 작업 절차

### 1. 참조 기준을 읽고 대상 경로를 고정한다

- 코드 구조 분석 기준은 [references/codebase-analysis-guide.md](references/codebase-analysis-guide.md)를 따른다.
- 분석 결과를 active RULES 문서로 작성하는 기준은 [references/backend-rule-writing-guide.md](references/backend-rule-writing-guide.md)를 따른다.
- 분석 대상 backend 코드베이스 경로를 정한다. 생략되면 현재 작업 디렉토리에서 backend 소스, build file, entrypoint, module root를 찾아 후보를 제안한다.
- 문서 출력 경로는 별도 요청이 없으면 `docs/rules/backend`로 고정한다.
- 분석 대상이 backend인지, 여러 backend 서비스 중 어느 경계를 기준으로 문서화할지 모호하면 파일을 수정하기 전에 사용자에게 질문한다.

### 2. 전면 재작성 경계를 확인한다

- 이 스킬은 target 문서를 현재 코드 기준으로 다시 작성한다.
- 기존 `docs/rules/backend` 문서는 기존 출력물 또는 템플릿으로만 본다. 현재 코드 근거보다 우선하지 않는다.
- 기존 target 문서에 사람이 작성한 프로젝트 고유 판단이 있고 사용자가 전면 재작성을 명시하지 않았다면, 수정 전에 재작성 허용 범위를 확인한다.
- 확인 없이 기존 문서 문구를 이어 붙이거나, 오래된 문서 구조를 보존하기 위해 현재 코드 분석 결과를 왜곡하지 않는다.

### 3. 현재 backend 코드베이스를 분석한다

- 멀티 모듈인지 단일 모듈인지 먼저 구분한다.
- 실제 모듈명, 패키지명, 클래스 역할, 어노테이션, 인터페이스, 의존 방향을 근거로 기록한다.
- 안정적인 반복 패턴, 상황별 변형, 현행 예외, 충돌 신호, 단발성 구현을 구분한다.
- 대형 코드베이스에서는 전체 정독보다 census, 우선순위화, 제한 샘플링, confidence report를 우선한다.
- 코드 근거만으로 새 코드 기준, 예외 허용 범위, 규칙 우선순위, 문서 소유권을 판단하기 조금이라도 모호하면 `충돌/확인 필요`로 분류하고 사용자 질문 후보로 남긴다.

### 4. 문서 작성 전 모호성을 질문한다

- 다음 항목은 active RULES 문서에 확정 규칙으로 쓰기 전에 사용자에게 질문한다.
  - 둘 이상의 active 구현 방식 중 표준을 코드만으로 판단하기 어려운 경우
  - 오래된 구현처럼 보이지만 현재 코드가 의존하는 예외 범위가 불명확한 경우
  - 보안, 데이터 정합성, 운영 안전성에 영향을 주는 규칙 후보
  - backend 서비스 경계, 모듈 경계, 테스트 기준이 코드만으로 불명확한 경우
  - target 문서의 전면 재작성 허용 범위가 불명확한 경우
- 질문에는 관찰한 코드 근거, 가능한 선택지, 선택지별 `docs/rules/backend` 반영 결과를 포함한다.
- 사용자 답변을 받기 전에는 모호한 후보를 강제 규칙으로 쓰지 않는다.
- 사용자가 후속 결정으로 넘기라고 승인한 항목만 보고서의 `확인 필요` 또는 후속 backlog로 남긴다.

### 5. target RULES 문서를 전면 작성한다

- [references/backend-rule-writing-guide.md](references/backend-rule-writing-guide.md)에 따라 분석 후보의 RULES 문서 위치와 rule id를 확정한다.
- 포함 범위에 있는 target 문서를 현재 코드 근거와 사용자 답변 기준으로 작성하거나 재작성한다.
- `docs/rules/backend/README.md`는 active RULES 문서 맵, 읽기 순서, 우선순위를 소유한다.
- 같은 규칙 원문을 여러 RULES 문서에 중복하지 않는다.
- 과거 TDD, 상세 전략, 참고 문서는 active `docs/rules/backend` 안에 두지 않는다.
- 모호성 질문 게이트를 통과하지 못한 후보는 active RULES 문서에 확정 규칙으로 반영하지 않는다.

### 6. 검증하고 보고한다

- 생성·수정한 문서가 실제 코드 근거와 연결되는지 샘플 기준으로 확인한다.
- 변경 범위, 코드 근거, coverage/confidence, 남은 불확실성, 사용자 확인이 필요한 항목을 보고한다.
- target 문서가 바뀌었다면 `docs/rules/backend/README.md` 문서 맵이 정확한지 확인한다.
- 이 SKILL.md 자체를 수정한 뒤에는 `python3 .agents/skills/skill-artifact-write/scripts/check_skill_artifact.py .agents/skills/backend-rules-derive-from-code/SKILL.md`를 실행한다.

## 결과물

작업이 완료되면 아래 결과 상태가 존재해야 한다.

- 현재 backend 코드베이스 기준으로 작성된 active `docs/rules/backend` RULES 문서
- 변경한 문서 경로 목록
- 코드 근거와 연결된 주요 문서화 판단
- 분석 범위, 제외 범위, coverage/confidence, 남은 미분석 영역
- 사용자에게 질문한 모호한 항목, 사용자 답변으로 확정한 결정, 남은 불확실성과 후속 확인 항목
- active RULES 문서를 반영한 `docs/rules/backend/README.md` 문서 맵

## 품질 검증 기준

결과물은 아래 기준을 만족해야 완료로 인정한다.

- 문서화된 내용은 코드에서 확인한 사실 또는 명시된 사용자 확인에 근거한다.
- 규칙 문서는 단일 예시만 설명하지 않고 적용 범위, 반복 근거, 변형, 예외, 주의점을 함께 담는다.
- 코드에서 확인되지 않은 정책, 실행 방법, 구현 패턴을 만들지 않는다.
- 기존 문서 문구가 현재 코드 근거보다 우선하지 않는다.
- 판단이 모호한 후보는 active RULES 반영 전에 사용자에게 질문했다.
- 사용자 답변을 받지 못한 모호한 후보는 확정 규칙으로 승격하지 않았다.
- `docs/rules/backend/README.md` 문서 맵이 active RULES 문서를 정확히 반영한다.
- `docs/rules/backend` 하위에 과거 TDD, 상세 전략, 참고 문서가 active Source of Truth처럼 남아 있지 않다.
- `docs/backend` 하위 문서는 산출물 또는 참고 자료 역할로만 해석된다.
- RULES 문서 사이에 원문 중복과 우선순위 충돌이 남아 있지 않다.
- 대형 코드베이스는 제한 샘플링 예산과 confidence report를 남기고 high-confidence 영역부터 규칙화한다.
- 스킬 문서 자체를 수정했다면 `check_skill_artifact.py` 검증을 통과한다.
