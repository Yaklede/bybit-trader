# Backend Rule Writing Guide

## 목적

코드 분석 결과를 `docs/rules/backend` active RULES 문서 중 어디에 어떤 구조로 작성할지 정한다.

이 문서는 문서 소유권, 관심사 라우팅, RULES 공통 구조, rule id, 전면 작성 원칙, 작성 금지 사항, 완료 전 점검만 소유한다. 코드 신호를 찾는 방법은 [codebase-analysis-guide.md](codebase-analysis-guide.md)를 따른다.

## 문서 소유권

| 문서 | 소유 내용 |
|------|-----------|
| `docs/rules/backend/README.md` | active RULES 문서 맵, 작업별 읽기 순서, 전역 우선순위 |
| `docs/rules/backend/PACKAGE_STRUCTURE.md` | 패키지, 파일, command/query 배치 규칙 |
| `docs/rules/backend/ARCHITECTURE_RULES.md` | 모듈 책임, 레이어 책임, 의존 방향, CQRS, port/adapter, transaction boundary |
| `docs/rules/backend/CODING_RULES.md` | naming, DTO, mapper, exception, validation, Kotlin 작성 규칙 |
| `docs/rules/backend/TESTING_RULES.md` | test level, fixture, mocking, architecture test, fresh verification |
| `docs/rules/backend/API_RULES.md` | HTTP contract, request/response, error, pagination, `.http` artifact |
| `docs/rules/backend/DATA_RULES.md` | schema, transaction, query, persistence adapter |
| `docs/rules/backend/SECURITY_RULES.md` | authn/authz, session, secret, sensitive data, CORS |
| `docs/rules/backend/OPERATIONS_RULES.md` | profile, config, logging, health, deploy, CI |

같은 신호가 여러 문서에 걸치면 가장 구체적인 문서에 원문을 두고 다른 문서에서는 링크와 적용 맥락만 둔다.

## 관심사 라우팅

| 신호 | 위치 |
|------|------|
| 패키지/파일/command-query 배치 | `PACKAGE_STRUCTURE.md` |
| 모듈 책임, 레이어 책임, 의존 방향 | `ARCHITECTURE_RULES.md` |
| port/interface/adapter 경계 | `ARCHITECTURE_RULES.md` |
| CQRS command/query 흐름 책임 | `ARCHITECTURE_RULES.md` |
| transaction boundary의 계층 책임 | `ARCHITECTURE_RULES.md` |
| DTO, mapper, exception, validation, naming | `CODING_RULES.md` |
| unit/slice/integration/security test 기준 | `TESTING_RULES.md` |
| controller, API DTO, error response, `.http` | `API_RULES.md` |
| entity, repository, migration script, query, consistency | `DATA_RULES.md` |
| password, session, OAuth, secret, CORS | `SECURITY_RULES.md` |
| profile, config, logging, health, deploy, CI | `OPERATIONS_RULES.md` |

## 공통 문서 구조

모든 active backend RULES 문서는 아래 섹션을 가진다.

```md
# {Title}

## 목표

## 적용 범위

## 정의

## 규칙

## 우선순위
```

작성 기준:

- `목표`는 이 문서가 강제하려는 품질과 방지하려는 실패를 짧게 쓴다.
- `적용 범위`는 적용되는 코드, 작업 유형, 다른 RULES 문서와의 경계를 쓴다.
- `정의`는 규칙 해석에 필요한 용어만 둔다.
- `규칙`은 reviewer가 인용할 수 있는 rule id를 붙인다.
- `우선순위`는 규칙 충돌, 현행 예외, 사용자 확인 필요 조건을 쓴다.
- 확인되지 않은 내용은 강제 규칙으로 쓰지 않는다.

## Rule ID

권장 prefix:

| 문서 | Prefix |
|------|--------|
| `README.md` | `BACKEND-DOC`, `BACKEND-PRIORITY` |
| `PACKAGE_STRUCTURE.md` | `PKG` |
| `ARCHITECTURE_RULES.md` | `ARCH` |
| `CODING_RULES.md` | `CODE` |
| `TESTING_RULES.md` | `TEST` |
| `API_RULES.md` | `API` |
| `DATA_RULES.md` | `DATA` |
| `SECURITY_RULES.md` | `SEC` |
| `OPERATIONS_RULES.md` | `OPS` |

형식:

```text
{PREFIX}-{AREA}-{NUMBER}: {규칙 문장}
```

예:

```text
ARCH-DEP-001: Domain layer는 application, delivery adapter, infrastructure adapter, framework type에 의존하지 않는다.
```

## 전면 작성 원칙

- `docs/rules/backend` 문서는 현재 backend 코드베이스와 사용자 답변을 기준으로 작성한다.
- 기존 target 문서는 이전 출력물 또는 템플릿으로만 참고한다. 현재 코드 근거보다 우선하지 않는다.
- target 문서가 이미 있고 사람이 작성한 프로젝트 고유 판단이 포함되어 있으면, 전면 재작성 전에 사용자에게 허용 범위를 확인한다.
- 기존 문구를 이어 붙이는 방식으로 규칙을 만들지 않는다.
- 코드 근거가 high 또는 medium confidence인 후보만 규칙으로 쓴다.
- low confidence 후보는 확정 규칙으로 쓰지 않고 보고서의 `확인 필요` 또는 후속 backlog에 남긴다.
- 오래된 구현처럼 보이는 패턴도 현재 active code가 의존하면 삭제 대상이 아니라 `현행 예외` 또는 `확인 필요` 후보로 다룬다.
- 문서가 현재 코드에서 확인되지 않는 팀 정책을 담아야 한다면 먼저 사용자 확인을 받는다.

## 질문해야 하는 경우

- target 문서 전면 재작성 허용 범위가 불확실할 때
- 둘 이상의 active 구현 방식 중 새 코드 기준을 코드만으로 판단하기 어려울 때
- 새 규칙이 보안, 데이터 정합성, 운영 안전성에 영향을 줄 때
- 특정 신호가 어느 RULES 문서의 책임인지 애매할 때
- 오래된 구현처럼 보이는 패턴을 신규 코드에도 적용할지, 기존 코드의 예외로만 둘지 모호할 때
- 코드 근거는 있지만 팀 의도, 전환 방향, 표준화 방향을 확인하지 않으면 규칙이 과도하게 강제될 수 있을 때

질문할 때는 관찰한 코드 근거, 가능한 선택지, 선택지별 문서 반영 결과를 함께 제시한다. 사용자 답변 전에는 모호한 내용을 active RULES의 확정 규칙으로 쓰지 않는다.

## 금지 규칙

- `docs/rules/backend` 하위에 과거 TDD, 상세 전략, 참고 문서를 active 문서처럼 남기지 않는다.
- `docs/backend` 하위 산출물을 현재 backend 규칙 Source of Truth처럼 링크하지 않는다.
- 코드에서 확인되지 않은 규칙을 확정 규칙처럼 작성하지 않는다.
- Low confidence 후보를 강제 규칙으로 승격하지 않는다.
- 기존 target 문서 문구를 코드 근거 없이 보존하지 않는다.
- 같은 규칙을 여러 RULES 문서에 원문으로 중복 작성하지 않는다.
- RULES 문서에 긴 구현 절차나 튜토리얼을 넣지 않는다.
- 오래된 참고 문서를 현재 Source of Truth처럼 링크하지 않는다.

## 완료 전 점검

- `docs/rules/backend` 바로 아래 active RULES target 문서만 존재한다.
- 각 active RULES 문서가 공통 섹션 5개를 가진다.
- 모든 강제 규칙은 rule id를 가진다.
- 오래된 참고 문서가 active Source of Truth처럼 링크되지 않는다.
- target 문서 목록과 읽기 순서가 `docs/rules/backend/README.md`에 반영되었다.
- 코드 근거가 낮은 후보가 강제 규칙으로 승격되지 않았다.
- 현행 예외 또는 혼재 패턴에서 판단이 모호한 후보는 사용자 확인 없이 확정 규칙으로 쓰지 않았다.
