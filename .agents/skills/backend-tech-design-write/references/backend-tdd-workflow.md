# Backend TDD 작성 흐름

## 목적

백엔드 기술설계문서를 작성할 때 어떤 순서로 범위, 근거, 설계 판단, 저장 결과를 만들지 정의한다.

## 적용 범위

이 문서는 작성 과정의 흐름을 소유한다. 최종 문서의 섹션 구조와 표 형식은 [backend-tdd-template.md](backend-tdd-template.md)가 소유한다.

## 전체 흐름

```text
1. 설계 대상 식별
2. 프로젝트 컨텍스트 수집
3. 설계 배경과 요구사항 정의
4. 현행 시스템 분석
5. 아키텍처와 계층 책임 설계
6. 도메인 모델과 데이터 설계
7. 트랜잭션과 정합성 설계
8. 예외와 실패 처리 설계
9. 동시성, 성능, 확장성 검토
10. 문서 저장
```

## 1. 설계 대상 식별

스킬 호출 인수에서 설계 대상을 추출한다.

- 설계 대상 기능 또는 리팩토링 범위를 한 문장으로 고정한다.
- 신규 기능인지 기존 기능 변경인지 판별한다.
- 포함 범위와 제외 범위를 분리한다.
- 범위가 모호하면 사용자에게 구체적으로 질문한다.

질문이 필요한 예:

- 전체 취소와 부분 취소처럼 시나리오 경계가 모호한 경우
- 외부 시스템 연동 포함 여부가 불명확한 경우
- 동시성, 응답 시간, 가용성 같은 비기능 요구가 설계에 영향을 주는 경우

## 2. 프로젝트 컨텍스트 수집

설계의 근거가 되는 문서와 코드만 선별해 읽는다.

- `AGENTS.md`
- `docs/rules/backend/README.md`
- `docs/rules/backend/PACKAGE_STRUCTURE.md`
- `docs/rules/backend/ARCHITECTURE_RULES.md`
- `docs/rules/backend/CODING_RULES.md`
- `docs/rules/backend/TESTING_RULES.md`
- `docs/rules/backend/API_RULES.md`
- `docs/rules/backend/DATA_RULES.md`
- `docs/rules/backend/SECURITY_RULES.md`
- `docs/rules/backend/OPERATIONS_RULES.md`
- 관련 backend 코드의 app, application, domain, storage, external 경로
- 관련 DB schema, migration, seed, fixture, test 코드

탐색 기준:

- DB 설계가 관련되면 실제 schema와 migration을 먼저 확인한다.
- 도메인 규칙이 관련되면 domain model, error code, invariant 검증 코드를 확인한다.
- UseCase나 Flow가 관련되면 application 계층의 command/query 분리와 transaction 경계를 확인한다.
- 외부 연동이 관련되면 adapter, client, timeout, retry, failure mapping을 확인한다.
- API 변경이 관련되면 controller, request/response DTO, exception response를 확인한다.

## 3. 설계 배경과 요구사항 정의

문서 독자가 "왜 이 설계가 필요한가"를 이해할 수 있게 배경, 목표, 비목표, 제약을 정리한다.

- 배경은 비즈니스 문제와 현재 시스템 제약을 함께 설명한다.
- 설계 목표는 달성 기준과 이유를 포함한다.
- 비목표는 범위 확장을 막기 위해 반드시 명시한다.
- 제약은 프로젝트 architecture, infra, 비기능 요구를 확인한 근거로 적는다.

## 4. 현행 시스템 분석

설계 대상이 영향을 주는 현재 구조를 분석한다.

- 관련 도메인 구조와 참조 관계를 ASCII 다이어그램으로 표현한다.
- 요청 처리 흐름을 app, application, domain, storage, external 경계 기준으로 추적한다.
- 현재 schema와 변경이 필요한 필드를 구분한다.
- 유사 도메인이 있으면 패턴을 비교해 일관성을 확인한다.

## 5. 아키텍처와 계층 책임 설계

각 계층에 어떤 책임을 둘지 판단한다.

- domain에는 비즈니스 규칙, invariant, 상태 전이를 둔다.
- application에는 use case orchestration, policy, port 호출, transaction 경계를 둔다.
- storage/external에는 기술 구현과 외부 시스템 의존을 둔다.
- app에는 transport, request/response mapping, API error response를 둔다.
- Command와 Query가 분리되면 흐름과 책임을 별도로 기술한다.
- 최소 2개 이상의 설계 대안을 비교하고 채택 이유를 명시한다.

## 6. 도메인 모델과 데이터 설계

도메인 모델, aggregate 경계, 데이터 저장 구조를 설계한다.

- aggregate 경계와 ID 참조 방식을 명확히 한다.
- 도메인 모델의 책임, invariant, 주요 행위, 상태 전이를 정리한다.
- DB schema는 프로젝트의 실제 DDL 관리 방식에 맞춘다.
- Domain, Entity, DTO 간 변환 경로를 명시한다.

## 7. 트랜잭션과 정합성 설계

운영 장애를 줄이기 위해 transaction boundary와 consistency 전략을 먼저 고정한다.

- 연산별 transaction 시작점, 범위, 격리 수준, 사유를 표로 정리한다.
- 강한 일관성과 최종 일관성 중 무엇이 필요한지 판단한다.
- 여러 aggregate를 하나의 transaction으로 묶으면 타당성을 설명한다.
- 이벤트 기반 처리가 있으면 발행 시점, 구독자, 실패 대응을 포함한다.

## 8. 예외와 실패 처리 설계

정상 흐름뿐 아니라 실패 흐름을 설계한다.

- 비즈니스 예외, 미존재, 상태 전이 불가, 권한, 외부 시스템 실패를 분류한다.
- ErrorCode, error type, 사용자 메시지, 복구 전략을 함께 정리한다.
- 외부 연동이 있으면 timeout, network failure, delayed response를 포함한다.
- 멱등성이 필요한 연산은 중복 요청 처리 전략을 명시한다.

## 9. 동시성, 성능, 확장성 검토

경합 지점과 병목 가능성을 현재 요구 수준에 맞게 다룬다.

- 경합 자원별로 distributed lock, optimistic lock, pessimistic lock, queue, eventual consistency 중 선택한다.
- 선택한 방식의 이유와 대기 시간, retry, fallback을 적는다.
- N+1, paging, index, cache, batch 처리 같은 성능 우려를 확인한다.
- 현재 열어둔 확장 포인트와 의도적으로 제한한 지점을 함께 설명한다.

## 10. 문서 저장

최종 문서는 `.agents/runs/{run_id}/outputs/M*/tdd-{feature-slug}.md` 또는 사용자가 명시한 output artifact 경로에 저장한다.

- `{feature-slug}`는 설계 대상의 핵심을 2~4개 영문 kebab-case 단어로 축약한다.
- 관련 설계 문서가 이미 있으면 새 파일보다 기존 문서 갱신이 적절한지 판단한다.
- TDD는 active `docs/rules/backend` Source of Truth가 아니라 작업 산출물이다.
- 반복적으로 재사용해야 하는 설계 판단은 TDD에 숨기지 않고 `docs/rules/backend/*RULES.md`로 승격할지 사용자에게 제안한다.
- 완료 전 [backend-tdd-template.md](backend-tdd-template.md)의 완료 기준을 확인한다.

## 검증

- 설계 판단이 확인된 문서와 코드 근거에 기반하는가
- 계층 책임과 transaction boundary가 서로 모순되지 않는가
- 실패 시나리오와 검증 계획이 정상 흐름만 다루지 않는가
- 저장 경로가 run artifact 또는 사용자가 명시한 output artifact 경로인가
