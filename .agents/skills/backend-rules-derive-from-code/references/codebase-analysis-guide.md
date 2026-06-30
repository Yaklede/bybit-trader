# Backend Codebase Analysis Guide

## 목적

현재 백엔드 코드베이스에서 `docs/rules/backend`에 반영할 실제 구조, 반복 패턴, 현행 예외, 충돌 신호, confidence를 찾는다.

이 문서는 코드 근거 수집과 분석 메모 형식만 소유한다. 분석 결과를 어느 RULES 문서에 어떻게 쓸지는 [backend-rule-writing-guide.md](backend-rule-writing-guide.md)를 따른다.

## 분석 원칙

- 코드에서 확인한 사실만 문서 후보로 삼는다.
- 클래스명, 패키지명, 어노테이션, 인터페이스, 설정 파일, 테스트 구조처럼 관찰 가능한 근거를 남긴다.
- 이름보다 책임과 의존 방향을 우선하되, 최종 문서 용어는 프로젝트가 실제로 쓰는 이름을 우선한다.
- 단발성 구현과 반복 구현 전략을 구분한다.
- 불확실한 부분은 추측하지 않는다.
- active RULES 문서 반영에 영향을 주는 모호성은 사용자에게 질문하고, 답변 전에는 분석 메모의 `불확실한 부분`에 남긴다.

## 규모별 분석 운영

| 규모 | 기본 방식 | 완료 기준 |
|------|-----------|-----------|
| 10~1,000 LOC | 후보 파일 대부분을 직접 읽는다. | 주요 파일을 확인하고 근거 제한을 명시한다. |
| 1,000~10,000 LOC | 전체 파일 목록을 만든 뒤 단위별 대표 파일을 읽는다. | 모든 아키텍처 단위가 최소 1개 이상 근거를 가진다. |
| 10,000~100,000 LOC | repo census와 suffix/annotation/import 검색으로 후보를 만든 뒤 계층화 샘플링한다. | 주요 단위와 shared abstraction을 우선 문서화하고 coverage를 남긴다. |
| 100,000~1,000,000 LOC | census -> 우선순위화 -> 제한 샘플 -> confidence report 순서로 진행한다. | high-confidence 영역만 규칙화하고 나머지는 backlog/확인 필요로 둔다. |

대형 저장소 절차:

1. 제외 경로를 먼저 정한다.
2. `rg --files` 기반으로 실제 소스, 테스트, 설정, script, generated 후보를 분리한다.
3. build file, module file, package root, entrypoint, public contract, shared abstraction을 census로 요약한다.
4. fan-in/fan-out이 큰 공통 모듈, 외부 노출 entrypoint, transaction/persistence 중심 흐름, 테스트 지원 코드를 우선순위로 둔다.
5. 각 아키텍처 단위는 샘플링 예산 안에서 읽고, 문서마다 coverage/confidence를 남긴다.
6. 샘플 밖의 영역은 추측하지 않고 `미분석 영역` 또는 `확인 필요`로 둔다.
7. 새 코드 기준으로 삼을 주류 패턴이 코드만으로 명확하지 않으면 문서 반영 전에 사용자에게 질문한다.

기본 제외 경로:

- VCS/IDE/build: `.git`, `.idea`, `.gradle`, `build`, `target`, `out`, `dist`
- dependency/vendor: `node_modules`, `vendor`, `.m2`, `.npm`, `.yarn`, `Pods`
- generated: `generated`, `generated-sources`, `build/generated`, `openapi/generated`, `graphql/generated`
- binary/report: `coverage`, `reports`, `*.class`, `*.jar`, `*.war`, `*.zip`

주의:

- generated code가 public contract의 원천이면 제외하지 말고 `generated contract`로 표시한다.
- database script, fixture, snapshot은 기본 구현 전략 근거로 쓰지 않는다. 데이터 관리 정책이나 테스트 전략을 볼 때만 제한적으로 샘플링한다.
- 언어·프레임워크가 섞인 저장소는 runtime 또는 service boundary별로 나눈다.

## 샘플링 기준

- 단위별 entrypoint: 최대 3개. 서로 다른 업무 흐름을 우선한다.
- application/usecase/service 흐름: 단순 조회 1개, 상태 변경 1개, 복합 트랜잭션 또는 외부 연동 1개.
- persistence/external adapter: read/write 또는 성공/실패 흐름이 다르면 각각 1~2개.
- cross-cutting: transaction, validation, exception, logging, security, config는 각 축별 central abstraction 1~2개.
- tests: 단위 테스트 1~2개, 통합 또는 slice 테스트 1~2개, test support/fixture 1개.
- 100만 라인급에서는 첫 pass에서 상위 5~8개 아키텍처 단위만 high-confidence 후보로 다루고 나머지는 follow-up backlog로 둔다.

## 신뢰도 기준

| 신뢰도 | 기준 | 문서화 방식 |
|--------|------|-------------|
| High | 여러 업무 영역과 테스트/공통 추상화에서 같은 패턴이 확인된다. | 규칙으로 작성 가능 |
| Medium | 2개 이상 근거가 있으나 특정 모듈이나 업무에 편중된다. | 적용 범위와 예외를 제한해 작성 |
| Low | 근거가 1개이거나 샘플 밖 영향이 크다. | 규칙화하지 않고 확인 필요로 기록 |

## 확인할 코드 신호

프로젝트 구조:

- `settings.gradle.kts`, `build.gradle.kts`, module dependency
- source/test root, package root, application entrypoint
- component scan, profile, framework configuration

아키텍처 단위:

- module/package 책임
- public contract와 내부 구현
- Controller, Handler, Listener, Job, UseCase, Service, Repository, Adapter, Client, Config suffix
- `@RestController`, `@Service`, `@Component`, `@Transactional`, `@Entity`, `@Configuration` annotation

의존 경계:

- module/package import 방향
- domain model이 persistence entity나 web DTO를 참조하는지
- application/usecase가 infrastructure implementation을 직접 참조하는지
- adapter implementation이 port interface를 구현하는지
- 테스트가 어떤 public contract를 기준으로 작성되는지

정책 신호:

- 보안: 인증/인가, session/token, secret, sensitive data, CORS
- 데이터: schema, database script, transaction, query, consistency, seed data
- API: request/response, error response, pagination, `.http`
- 운영: profile, config, logging, health, deploy, CI
- 테스트: test level, fixture, mocking, architecture test, fresh verification

## 반복 패턴 분류

| 분류 | 인정 기준 | 처리 |
|------|-----------|------|
| 권장 주류 | 여러 기능에서 반복되고 테스트 또는 central abstraction과 연결된다. | RULES 후보로 둔다. |
| 상황별 변형 | 특정 업무, 기술 제약, 성능/정합성 요구에서 반복된다. | 적용 조건을 제한해 RULES 후보로 둔다. |
| 현행 예외 | 오래된 패키지나 부분 전환 구간에서 반복되며 아직 active code가 의존한다. | 신규 코드 적용 여부가 모호하면 사용자에게 질문한다. |
| 충돌/확인 필요 | 둘 이상의 active 패턴이 공존하지만 우선순위를 코드만으로 판단하기 어렵다. | active RULES 반영 전에 사용자에게 질문한다. |
| 단발성 제외 | 한 기능에만 있고 공통 추상화나 반복 근거가 없다. | 규칙화하지 않는다. |

현행 예외·혼재 신호:

- `v1`/`v2`, `legacy`, `old`, `new`, `deprecated`, `adapter` 같은 이름 또는 주석
- 같은 책임을 가진 Controller/Service/Repository가 패키지나 suffix만 다르게 존재함
- 일부 코드는 interface/port를 쓰고 일부 코드는 구현체나 framework API를 직접 참조함
- transaction, validation, exception mapping, DTO 변환 위치가 업무 영역마다 다름
- 테스트 방식이 mock 중심, slice test, integration test로 나뉘며 대상 코드와 함께 반복됨
- pending-work 주석, deprecated annotation, suppressed warning, feature flag, profile 분기가 특정 구현 방식을 감쌈

## 분석 메모 형식

```text
[BACKEND AREA] {package-structure|architecture|coding|testing|api|data|security|operations}
문서 후보:
- {docs/rules/backend 하위 경로}

분석 범위:
- included: {읽은 모듈/패키지}
- excluded: {제외한 generated/vendor/build/test fixture 등}
- coverage/confidence: {High|Medium|Low 및 이유}

코드 근거:
- {모듈 또는 패키지 경로}: {관찰 내용}

반복 패턴:
- {패턴명}: {권장 주류|상황별 변형|현행 예외|충돌/확인 필요|단발성 제외}

의존 방향:
- depends on: {단위 목록}
- used by: {단위 목록}

불확실한 부분:
- {있다면 코드 근거, 선택지, 사용자에게 물어볼 질문}
```

## 분석 완료 조건

- 문서 후보마다 최소 하나 이상의 코드 근거가 있다.
- 대형 코드베이스에서는 제외 경로, 샘플링 범위, coverage/confidence가 문서 후보마다 기록되어 있다.
- 실제 아키텍처 단위와 플레이북 개념 레이어를 혼동하지 않았다.
- 정책 후보와 구현 패턴 후보가 분리되어 있다.
- 주류 패턴, 변형, 현행 예외, 충돌/확인 필요, 단발성 제외가 구분되어 있다.
- 실행 정보는 설정 파일이나 빌드 스크립트 근거와 연결되어 있다.
- 불확실한 항목은 추측으로 채우지 않고 별도로 표시했다.
