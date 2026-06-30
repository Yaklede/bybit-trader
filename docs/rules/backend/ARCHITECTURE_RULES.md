<!-- OPENDOCK:START id=files:docs/rules/backend/ARCHITECTURE_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/ARCHITECTURE_RULES.md -->
# Backend Architecture Rules

## 목표

백엔드 코드가 어떤 규모로 커져도 레이어 책임, 의존 방향, port/adapter 경계, transaction boundary가 유지되게 한다.

AI 에이전트는 이 문서를 기준으로 설계, 구현, architecture review에서 위반 여부를 판단한다.

## 적용 범위

- Domain, application, delivery adapter, persistence adapter, external adapter, security adapter, support module 변경에 적용한다.
- UseCase 추가, 도메인 모델 변경, port/adapter 변경, transaction boundary 변경, 모듈 의존성 변경에 적용한다.
- HTTP 계약 자체는 [API_RULES.md](API_RULES.md), DB 세부사항은 [DATA_RULES.md](DATA_RULES.md), 보안 세부사항은 [SECURITY_RULES.md](SECURITY_RULES.md)를 따른다.

## 정의

- Domain layer: framework와 런타임 기술을 모르는 비즈니스 규칙 계층.
- Application layer: UseCase 실행, orchestration, transaction boundary, port 계약을 소유하는 계층.
- Delivery adapter: HTTP controller, batch job, message listener처럼 외부 요청을 application으로 전달하는 adapter.
- Outbound port: application이 자기 책임 밖의 자원이나 다른 개념 영역에 요구하는 추상 계약.
- Inbound port: delivery adapter가 호출하는 application UseCase 계약.
- Command port: command 수행에 필요한 상태 변경 위임, 전제 조건 확인, 참조 조회를 위한 outbound port contract.
- Query port: 화면 조회 또는 query flow 응답 조립을 위한 outbound port contract.
- Infrastructure adapter: DB, cache, external API, filesystem, message broker 같은 런타임 기술 구현.
- Transaction boundary: 하나의 정합성 단위로 commit 또는 rollback되어야 하는 application 작업 범위.
- Command: 상태 변경을 요청하는 application flow.
- Query: 상태를 변경하지 않고 데이터를 조회하는 application flow.
- CQRS: command flow와 query flow의 진입점, 책임, DTO를 분리해 상태 변경과 조회의 결합을 줄이는 구조.
- User action service: 사용자 행위나 업무 시나리오를 application layer에서 orchestration하는 service.
- Domain command service: 특정 도메인 개념의 command transaction boundary를 소유하는 application service.
- Strategy: 런타임 조건에 따라 선택되어 실행되는 비즈니스 알고리즘 단위.
- Policy: 비즈니스 판단 기준과 정책 값을 캡슐화하는 단위. (Policy는 특정 도메인 개념에 대한 판단 기준(규칙)과 그 규칙을 구성하는 정책 값을 캡슐화한 객체)
- Domain service: 여러 도메인 개념 객체를 사용해 비즈니스 판단이나 계산을 수행하는 domain layer service.
- Logical resource key: 저장소 root, bucket, public base URL 같은 런타임 위치와 분리되어 도메인 리소스를 식별하는 상대 key 또는 path.
- Framework annotation: component registration, transaction demarcation처럼 business contract를 바꾸지 않고 런타임에 적용되는 framework annotation.
- Batch step component: Spring Batch step에서 Tasklet처럼 batch 실행 처리를 담당하는 delivery adapter 구성 요소.
- Batch job trigger controller: HTTP 요청으로 Spring Batch `Job`을 실행하는 batch 실행 앱의 delivery adapter.

## 규칙

- ARCH-DEP-001: Domain layer는 application, delivery adapter, infrastructure adapter, framework type에 의존하지 않는다.
- ARCH-DEP-002: Application layer의 business logic과 public contract는 domain과 port contract에만 직접 의존한다.
- ARCH-DEP-003: Delivery adapter는 application inbound port를 호출하고 domain model을 HTTP/session contract로 직접 노출하지 않는다.
- ARCH-DEP-004: Infrastructure adapter는 application outbound port를 구현하고 domain/application contract를 런타임 기술로 변환한다.
- ARCH-DEP-005: Support module은 domain/application 흐름을 호출하지 않는다.
- ARCH-DEP-006: Core layer는 실행 앱 모듈에 의존하지 않는다.
- ARCH-DEP-007: Application service는 프로젝트 표준으로 허용된 framework annotation을 사용할 수 있지만 framework API type을 method signature, DTO, domain model, port contract에 노출하지 않는다.
- ARCH-MOD-001: 백엔드는 실행 앱, core, adapter, support 책임을 구분하는 구조를 우선한다.
- ARCH-MOD-002: 실행 앱 모듈은 delivery adapter, 앱별 설정, security entrypoint, HTTP DTO, controller를 소유한다.
- ARCH-MOD-003: 실행 앱 모듈은 domain invariant, application port 구현체의 공통 계약, persistence entity를 소유하지 않는다.
- ARCH-MOD-004: core application은 UseCase, application service, command/query DTO, inbound port, outbound port interface를 소유한다.
- ARCH-MOD-005: adapter module은 application outbound port 구현체와 런타임 기술 세부사항을 소유한다.
- ARCH-MOD-006: batch, worker, scheduler는 실행 앱으로 취급하고 core application UseCase를 호출한다.
- ARCH-MOD-007: Spring Batch job은 scheduler가 Job을 실행하고, job config가 Job/Step wiring을 소유하며, 단일 작업으로 충분한 step 실행 처리는 Tasklet으로 batch job 개념 하위 component에 둔다.
- ARCH-MOD-008: Spring Batch Tasklet은 batch job parameter를 application UseCase command로 변환해 호출하고, JPA repository나 adapter 구현체와 직접 결합하지 않는다.
- ARCH-MOD-009: Spring Batch HTTP trigger controller는 `JobLauncher`와 `JobParameters`로 Job 실행만 담당하고, application UseCase, JPA repository, persistence adapter 구현체를 직접 호출하지 않는다.
- ARCH-PORT-001: outbound port interface는 application이 소유한다.
- ARCH-PORT-002: DB, cache, external API, filesystem, message broker 같은 infrastructure outbound port implementation은 application 밖의 adapter가 소유한다.
- ARCH-PORT-003: port contract에는 provider SDK, JPA entity, HTTP request/response, servlet session 같은 adapter type을 노출하지 않는다.
- ARCH-PORT-004: adapter exception은 `ApplicationException`과 해당 개념의 `ErrorCode`로 번역한다. `ErrorCode`와 `cause`만 전달하는 통과형 `*PortException`은 만들지 않는다.
- ARCH-PORT-005: outbound port는 command port와 query port를 분리한다.
- ARCH-PORT-006: command port는 command 수행에 필요한 상태 변경 위임과 참조 조회를 표현하고, query port는 화면 조회 또는 query flow 응답 조회를 표현한다.
- ARCH-PORT-007: infrastructure adapter는 하나의 application outbound port 구현을 우선하며, command port, query port, 서로 다른 업무 행위 port를 한 adapter class에서 동시에 구현하지 않는다.
- ARCH-DOMAIN-001: 도메인 불변식과 상태 전이는 domain model이 소유한다.
- ARCH-DOMAIN-002: Application service는 domain 규칙을 중복 구현하지 않고 domain 객체에 위임한다.
- ARCH-DOMAIN-003: 여러 도메인 개념 객체를 이용해 비즈니스 판단이나 계산을 수행하면 domain service로 분리한다.
- ARCH-DOMAIN-004: 도메인 리소스의 logical resource key 생성·검증 규칙은 domain value object가 소유한다. 저장소 root, bucket, public base URL, 실제 파일 쓰기 경로 조립은 infrastructure adapter 또는 runtime config 책임으로 둔다.
- ARCH-APP-001: Application service는 유스케이스 orchestration, validation sequencing, transaction boundary, port 호출 순서를 소유한다.
- ARCH-APP-002: UseCase끼리 직접 호출해 진입점을 결합하지 않는다.
- ARCH-APP-003: Application layer는 HTTP status, cookie, session, redirect, controller DTO를 알지 않는다.
- ARCH-APP-004: Application inbound UseCase interface의 구현체는 application service이며 `*Service` suffix를 사용한다.
- ARCH-APP-005: Application service 이름은 사용자 행위 또는 도메인 개념의 command 책임을 드러내야 한다.
- ARCH-APP-006: User action service는 하나의 사용자 행위나 업무 시나리오를 orchestration한다.
- ARCH-APP-007: Domain command service는 특정 도메인 개념의 command transaction boundary를 소유한다.
- ARCH-APP-008: User action service와 domain command service는 비즈니스 복잡도에 따라 분리한다.
- ARCH-APP-009: 두 service를 분리했을 때 user action service가 단순 proxy만 된다면 domain command service가 UseCase 구현체가 될 수 있다.
- ARCH-APP-010: 런타임 조건에 따라 구현을 선택해야 하는 비즈니스 알고리즘은 strategy로 분리한다.
- ARCH-APP-011: 비즈니스 판단 기준과 정책 값을 캡슐화해야 하면 policy로 분리한다.
- ARCH-APP-012: 같은 application 안의 다른 개념 영역에 command를 위임할 때는 해당 개념의 command 책임을 가진 application service로 접근한다.
- ARCH-APP-013: 같은 application 안의 다른 개념 영역에서 command 수행에 필요한 조회를 할 때는 command port로 접근한다.
- ARCH-APP-014: application 내부 개념 영역 간 command port는 port가 요구하는 행위가 구현 개념의 command 책임과 직접 일치할 때만 해당 개념의 command service가 구현한다. 호출 개념의 시나리오나 이름을 포함하는 contract라면 별도 application service가 port 구현을 맡고 실제 상태 변경이나 검증은 책임 개념의 command service에 위임한다. inbound UseCase끼리 직접 호출하지 않는다.
- ARCH-APP-015: 상위 resource에 종속된 nested resource 행위는 상위 resource의 user action service가 orchestration하고, child domain concept의 상태 변경은 해당 concept의 domain command service에 위임한다.
- ARCH-CQRS-001: Command flow는 상태 변경을 소유하고 query flow에 상태 변경 책임을 넘기지 않는다.
- ARCH-CQRS-002: Query flow는 데이터를 조회하고 응답을 조립할 수 있지만 상태를 변경하지 않는다.
- ARCH-CQRS-003: 상태 변경 UseCase와 조회 UseCase는 application inbound contract에서 분리한다.
- ARCH-CQRS-004: Command flow는 쓰기 transaction boundary를 소유하고 query flow는 필요한 경우 read-only transaction을 사용한다.
- ARCH-CQRS-005: Command flow는 검증이나 참조 조회를 위해 command port를 호출하고, query port나 query UseCase를 command 수행 진입점으로 사용하지 않는다.
- ARCH-CQRS-006: Command result와 query result는 유스케이스에 맞게 분리하되 infrastructure type을 노출하지 않는다.
- ARCH-TX-001: transaction boundary는 application 유스케이스 단위에서 결정한다.
- ARCH-TX-002: 외부 API 호출, 파일 I/O, 장기 네트워크 I/O는 DB transaction 내부에서 수행하지 않는다.
- ARCH-TX-003: transaction 안에서는 필요한 DB 작업만 수행하고 사용자 응답 조립, 외부 통신, 느린 계산을 분리한다.
- ARCH-TX-004: Command transaction boundary를 소유하는 service는 외부 API 호출, 파일 I/O, 장기 네트워크 I/O를 수행하지 않는다.
- ARCH-TX-005: Spring Batch Tasklet이 application command UseCase에 저장을 위임하면 transaction boundary와 port 호출 순서는 application service가 소유하고, Tasklet은 실행 위임과 결과 logging에 머문다.
- ARCH-TX-006: 하나의 command flow에 외부 API 또는 파일 I/O와 DB metadata 변경이 함께 필요하면, 외부 I/O를 수행하는 user action service와 DB transaction boundary를 소유하는 domain command service를 분리한다. DB command가 실패하면 user action service가 가능한 보상 처리를 수행한다.
- ARCH-SHARED-001: 둘 이상의 실행 앱에서 반복되는 web response나 exception envelope은 support module로 승격할 수 있다.
- ARCH-SHARED-002: support module로 승격한 코드는 업무 규칙이 아니라 cross-application contract여야 한다.
- ARCH-LEGACY-001: active legacy code가 규칙을 위반하면 새 코드에서 위반을 확산하지 않는다.
- ARCH-LEGACY-002: 규칙 위반을 즉시 제거할 수 없으면 적용 범위와 제거 계획을 TDD 또는 run artifact에 남긴다.

현재 wooyongdev backend 기준 아키텍처 단위:

| 모듈 | 분류 | 책임 |
|------|------|------|
| `modules/blog` | 실행 앱 | 공개 블로그 HTTP API와 방문자 인증 |
| `modules/cms` | 실행 앱 | 관리자 CMS HTTP API와 관리자 인증 |
| `modules/batch` | 실행 앱 | batch 실행 진입점 |
| `modules/core/domain` | core domain | 도메인 모델, 값 객체, domain error |
| `modules/core/application` | core application | UseCase, service, command/query result, port |
| `modules/internal/cache` | adapter | Redis cache adapter |
| `modules/internal/persistent` | adapter | JPA, QueryDSL, Flyway, MySQL persistence |
| `modules/external` | adapter | 외부 provider API adapter |
| `modules/support/web` | support | 공통 web response contract |

## 우선순위

- ARCH-PRIORITY-001: 의존 방향 위반은 naming, package convenience, 기존 관례보다 우선해서 해결한다.
- ARCH-PRIORITY-002: domain purity와 transaction safety는 구현 속도보다 우선한다.
- ARCH-PRIORITY-003: port/adapter 경계와 API shape가 충돌하면 application port contract를 먼저 안정화한 뒤 delivery/API DTO를 맞춘다.
- ARCH-PRIORITY-004: 레이어 책임이 애매하면 구현 전에 TDD에서 책임 소유자를 확정한다.
<!-- OPENDOCK:END id=files:docs/rules/backend/ARCHITECTURE_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/ARCHITECTURE_RULES.md -->
