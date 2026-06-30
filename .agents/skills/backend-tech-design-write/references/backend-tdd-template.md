# Backend TDD 템플릿

## 목적

백엔드 기술설계문서의 최종 산출물 구조와 섹션별 작성 기준을 정의한다.

## 적용 범위

이 문서는 TDD의 목차, 섹션 책임, 저장 규칙을 소유한다. 문서를 작성하는 순서는 [backend-tdd-workflow.md](backend-tdd-workflow.md)가 소유한다.

## 전체 구조

````markdown
# {기능명} Backend TDD

> 작성일: YYYY-MM-DD
> 상태: Draft | Reviewing | Approved | Superseded
> 대상 모듈: {대상 backend 모듈}

## 1. 설계 배경 및 목적

### 1.1 배경
{기능이 필요한 이유, 해결하려는 비즈니스 문제, 현재 시스템 제약}

### 1.2 설계 목표
1. **{목표}**: {달성 기준과 이유}

### 1.3 설계 비목표
- {이번 설계에서 제외하는 항목과 이유}

### 1.4 기술적 제약사항
- **아키텍처 제약**: {확인된 backend architecture 제약}
- **인프라 제약**: {확인된 infra 제약}
- **비기능 요구사항**: {성능, 가용성, 정합성 요구 수준}

## 2. 현행 시스템 분석

### 2.1 관련 도메인 구조
```text
{EntityA} (1) -> (N) {EntityB}  [{entityAId}로 참조]
```

### 2.2 현재 처리 흐름
```text
Controller -> UseCase -> Domain Service -> Port -> Adapter
```

### 2.3 현행 스키마 분석
| 테이블 | 주요 필드 | 현재 역할 | 변경 필요성 |
|--------|-----------|-----------|-------------|
| `{table}` | `{columns}` | {role} | {reason} |

## 3. 아키텍처 설계

### 3.1 계층별 책임 분배
| 계층 | 구성 요소 | 책임 | 설계 근거 |
|------|-----------|------|-----------|
| App | `{Component}` | {responsibility} | {reason} |
| Application | `{Component}` | {responsibility} | {reason} |
| Domain | `{Component}` | {responsibility} | {reason} |
| Storage/External | `{Component}` | {responsibility} | {reason} |

### 3.2 처리 흐름
{요청에서 응답까지의 command/query 흐름}

### 3.3 설계 대안 분석
| 대안 | 장점 | 단점 | 채택 여부 | 사유 |
|------|------|------|-----------|------|
| {alternative} | {pros} | {cons} | 채택/기각 | {reason} |

## 4. 도메인 모델 설계

### 4.1 애그리거트 경계
{aggregate 단위와 경계 결정 이유}

### 4.2 도메인 모델 상세
#### `{DomainClass}`
- 역할: {business responsibility}
- 불변식: {invariants}
- 주요 행위: {methods and meaning}
- 상태 전이: {state transition}

### 4.3 데이터 스키마 설계
```sql
-- 필요한 경우 실제 프로젝트 DDL 관리 방식에 맞춰 작성
```

### 4.4 데이터 변환 흐름
{Domain <-> Entity <-> DTO 변환 경로와 책임}

## 5. 트랜잭션 설계

### 5.1 트랜잭션 경계
| 연산 | 시작점 | 범위 | 격리 수준 | 사유 |
|------|--------|------|-----------|------|
| {operation} | {boundary} | {scope} | {isolation} | {reason} |

### 5.2 정합성 보장 전략
{강한 일관성 또는 최종 일관성 선택 이유}

### 5.3 이벤트 처리
| 이벤트 | 발행 시점 | 구독자 | 처리 방식 | 실패 대응 |
|--------|-----------|--------|-----------|-----------|
| {event} | {timing} | {handler} | {sync/async} | {recovery} |

## 6. 예외 및 실패 처리

### 6.1 예외 분류
| 예외 유형 | ErrorCode | 발생 조건 | Error Type | 사용자 메시지 |
|-----------|-----------|-----------|------------|---------------|
| {type} | `{ErrorCode}` | {condition} | {error type} | {message} |

### 6.2 실패 시나리오 및 복구 전략
| 시나리오 | 발생 가능성 | 영향 범위 | 복구 전략 |
|----------|-------------|-----------|-----------|
| {scenario} | 높음/중간/낮음 | {impact} | {recovery} |

### 6.3 멱등성 보장
{중복 요청과 재처리 부작용을 막는 방법}

## 7. 동시성 및 성능

### 7.1 동시성 제어
| 경합 지점 | 제어 방식 | 구현 방법 | 사유 |
|-----------|-----------|-----------|------|
| {resource} | {strategy} | {implementation} | {reason} |

### 7.2 성능 고려사항
| 항목 | 우려 사항 | 대응 전략 | 측정 기준 |
|------|-----------|-----------|-----------|
| {item} | {risk} | {strategy} | {metric} |

### 7.3 확장 가능성
{열어둔 확장 포인트와 의도적으로 제한한 지점}

## 8. 변경 파일 목록
| 파일 | 모듈 | 변경 유형 | 설명 |
|------|------|-----------|------|
| `{path}` | {module} | 생성/수정/삭제 | {description} |

## 9. 검증 계획
| 시나리오 | 유형 | 검증 내용 | 예상 결과 |
|----------|------|-----------|-----------|
| {scenario} | unit/integration/e2e/manual | {verification} | {expected} |

## 10. 리스크와 미결정 사항
- {risk or open question}

## 11. 완료 체크리스트
- [ ] 설계 배경과 목표가 현재 backend 구조와 요구사항에 연결된다.
- [ ] 계층별 책임, 도메인 모델, 트랜잭션 경계, 실패 처리 판단에 근거가 있다.
- [ ] 동시성, 성능, 확장 가능성의 의도적 제약과 열어둔 지점이 구분된다.
- [ ] 검증 계획이 변경 파일과 주요 시나리오를 빠짐없이 다룬다.
- [ ] TDD가 active `docs/rules/backend`가 아니라 run artifact 또는 사용자가 명시한 output artifact로 저장되었다.
````

## 섹션별 작성 기준

- 설계 배경: 요구사항 나열이 아니라 설계가 필요한 이유를 설명한다.
- 현행 시스템 분석: 신규 기능도 연관 도메인의 현재 구조와 일관성을 확인한다.
- 아키텍처 설계: 계층별 책임과 설계 대안 분석을 포함한다.
- 도메인 모델: invariant, 상태 전이, aggregate 경계를 명확히 한다.
- 트랜잭션 설계: transaction boundary와 consistency 선택 이유를 함께 적는다.
- 예외 및 실패 처리: 정상 케이스만 다루지 않고 복구 전략까지 포함한다.
- 동시성 및 성능: 현재 요구 수준에서 필요한 제어와 병목 가능성을 구분한다.
- 검증 계획: unit, integration, e2e, manual 중 필요한 방식을 명시한다.
- 완료 체크리스트: 문서 자체가 설계 판단, 근거, 검증 범위, 문서 맵 반영을 만족하는지 최종 확인한다.

## 저장 규칙

- 기본 저장 위치: `.agents/runs/{run_id}/outputs/M*/`
- 파일명: `tdd-{feature-slug}.md`
- `{feature-slug}`는 설계 대상의 핵심을 2~4개 영문 kebab-case 단어로 축약한다.
- 관련 설계 문서가 이미 있으면 새 파일을 만들기 전에 갱신 여부를 판단한다.
- TDD는 active `docs/rules/backend` Source of Truth에 추가하지 않는다.
- 반복적으로 재사용해야 하는 규칙은 별도 사용자 확인 후 `docs/rules/backend/*RULES.md`로 승격한다.

## 검증

- 빈 섹션이 남아 있지 않은가
- 설계 결정마다 근거가 있는가
- 확인되지 않은 프로젝트 규칙을 단정하지 않았는가
- 저장 위치가 run artifact 또는 사용자가 명시한 output artifact 경로인가
