<!-- OPENDOCK:START id=files:docs/rules/backend/PACKAGE_STRUCTURE.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/PACKAGE_STRUCTURE.md -->
# Backend Package Structure Rules

## 목표

백엔드 코드가 커져도 패키지와 파일 배치 기준이 흔들리지 않게 한다.

AI 에이전트는 이 문서를 기준으로 새 파일을 어느 패키지에 둘지, 기존 파일을 어떤 책임 단위로 이동할지, command/query 관련 타입을 어디에 배치할지 판단한다.

## 적용 범위

- `backend/modules/**/src/main`, `backend/modules/**/src/test` 아래 package와 file 배치에 적용한다.
- Controller, DTO, Mapper, UseCase, Service, Port, Adapter, Entity, Repository, Config, Security 관련 파일 위치 판단에 적용한다.
- 모듈 책임과 의존 방향은 [ARCHITECTURE_RULES.md](ARCHITECTURE_RULES.md), 빌드 설정과 배포 산출물은 [OPERATIONS_RULES.md](OPERATIONS_RULES.md)를 따른다.
- 파일 내부 구현 방식은 [CODING_RULES.md](CODING_RULES.md), 테스트 설계는 [TESTING_RULES.md](TESTING_RULES.md)를 따른다.

## 정의

- Package by capability: `post`, `tag`, `category`, `auth`, `member`, `admin`처럼 업무 능력 단위로 하위 패키지를 나누는 방식.
- Delivery package: controller, HTTP request/response DTO, request mapper처럼 외부 요청을 application UseCase가 이해하는 command/query로 변환해 전달하는 실행 앱 내부 진입점 패키지.
- Nested resource delivery: `/parents/{parentId}/children`처럼 HTTP path, 권한, 유효성, 생명주기가 상위 resource에 종속된 delivery endpoint.
- Application package: UseCase, command/query service, inbound/outbound port, application DTO를 담는 패키지.
- Nested resource application use case: 상위 resource나 업무 시나리오 안에서만 실행되고 독립 화면, 권한, 생명주기를 갖지 않는 application UseCase.
- Adapter package: persistence, external API, cache, security runtime처럼 application port 구현과 런타임 기술을 담는 패키지.
- Persistence concept package: `internal/persistent/{concept}`처럼 하나의 저장소 업무 개념에 대한 adapter, entity, repository, mapper를 묶는 패키지.
- Provider package: external adapter 안에서 특정 vendor, provider, external product domain의 client, config, DTO, error mapping, adapter 구현을 묶는 패키지.
- Provider code package: provider가 정의한 bank code, firm code, error code처럼 외부 시스템 코드값과 mapping enum을 담는 패키지.
- Batch job package: batch 실행 앱에서 `job/{concept}` 형태로 특정 batch 개념의 HTTP trigger controller, scheduler, Job/Step wiring, step 실행 component, job parameter model을 묶는 패키지.

## 규칙

- PKG-CAP-001: 모듈 내부 패키지는 기술 종류보다 업무 능력 단위를 우선한다.
- PKG-CAP-002: 업무 능력 내부에서 `dto`, `mapper`, `inbound`, `outbound`, `config`, `security` 같은 하위 패키지는 책임이 실제로 분리될 때만 만든다.
- PKG-CAP-003: 새 파일은 가장 가까운 책임 소유 패키지에 둔다. 임시 편의를 위해 실행 앱이나 support 패키지에 우회 배치하지 않는다.
- PKG-APP-001: 실행 앱 모듈의 delivery package는 controller, HTTP DTO, request mapper, 앱별 config, security entrypoint를 소유한다.
- PKG-APP-002: 실행 앱 모듈은 UseCase, command/query service, outbound port 구현체, persistence entity를 소유하지 않는다.
- PKG-BATCH-001: batch 실행 앱 root package는 application entrypoint, 앱 공통 `config`, `health`, `job` package만 소유한다.
- PKG-BATCH-002: batch 업무 개념 package는 root에 직접 만들지 않고 `job/{concept}` 아래에 둔다.
- PKG-BATCH-003: batch `job/{concept}/config`는 Job/Step wiring만 소유하고, HTTP trigger controller는 `job/{concept}/controller`, scheduler는 `job/{concept}/scheduler`, Tasklet 같은 step 실행 component는 `job/{concept}/service`, Job parameter model은 `job/{concept}/model`에 둔다.
- PKG-DELIVERY-001: 실행 앱의 delivery package는 업무 능력 단위로 먼저 나눈다.
- PKG-DELIVERY-002: Controller, HTTP request/response DTO, HTTP mapper는 같은 업무 능력 package 안에 둔다.
- PKG-DELIVERY-003: 한 업무 능력 package 안에서 DTO나 mapper가 늘어나 파일 탐색이 어려워지면 `dto`, `mapper` 하위 package로 분리한다.
- PKG-DELIVERY-004: 앱 전체에 적용되는 config, security entrypoint, exception handler는 특정 업무 능력 package가 아니라 실행 앱 공통 package에 둔다.
- PKG-DELIVERY-005: Delivery package는 HTTP request를 application command/query로 변환할 수 있지만 business rule, transaction boundary, port orchestration을 소유하지 않는다.
- PKG-DELIVERY-006: Delivery package의 response DTO는 HTTP 계약에 맞게 구성하되 domain model, JPA entity, provider DTO를 직접 노출하지 않는다.
- PKG-DELIVERY-007: Nested resource delivery endpoint의 Controller, HTTP DTO, HTTP mapper는 독립 child resource package보다 상위 resource를 소유한 delivery package에 둔다. 단, child resource가 상위 resource 없이 독립 API, 화면, 권한, 생명주기를 가진다면 child resource package를 둘 수 있다.
- PKG-CORE-001: core application package는 업무 능력 단위 아래에 UseCase, application service, application exception, application error code를 둔다.
- PKG-CORE-002: core application의 `inbound` package는 delivery adapter가 호출하는 UseCase contract를 소유한다.
- PKG-CORE-003: core application의 `outbound` package는 persistence, external, cache, security adapter가 구현할 port contract를 소유한다.
- PKG-CORE-004: application DTO는 사용하는 contract와 가장 가까운 `inbound`, `outbound`, 또는 업무 능력 package에 둔다.
- PKG-CORE-005: core application inbound command/query DTO는 `inbound/dto` 하위에 둔다. `inbound` root에는 delivery adapter가 호출하는 UseCase interface를 둔다.
- PKG-CORE-006: nested resource application use case의 UseCase, command service, inbound DTO는 child resource domain package가 아니라 상위 resource 또는 업무 시나리오를 소유한 application package에 둔다. 단, child resource가 상위 resource 없이 독립 API, 화면, 권한, 생명주기를 가지면 child resource application package를 둘 수 있다.
- PKG-CORE-007: 특정 도메인 개념의 상태 변경 transaction boundary를 소유하는 domain command service는 그 개념의 application package에 둔다. 상위 resource use case가 호출하더라도 transaction boundary의 도메인 소유권을 따라 배치한다.
- PKG-CQRS-001: 상태 변경 command contract와 조회 query contract는 이름과 파일을 분리한다.
- PKG-CQRS-002: `*CommandUseCase`와 `*QueryUseCase`는 application `inbound` package에 둔다.
- PKG-CQRS-003: `*CommandService`와 `*QueryService`는 해당 업무 능력의 application package에 두고, 복잡도가 높을 때만 `command`와 `query` 하위 패키지로 분리한다.
- PKG-CQRS-004: command DTO와 query DTO는 하나의 범용 request DTO로 합치지 않는다.
- PKG-ADAPTER-001: persistence adapter package는 업무 능력 단위를 반영하고 `Entity`, `JpaRepository`, persistence mapper, port adapter를 함께 소유한다.
- PKG-ADAPTER-002: external adapter package는 provider 또는 외부 도메인 단위로 client, provider DTO, mapper, port adapter를 묶는다.
- PKG-ADAPTER-003: persistence adapter class는 구현하는 outbound port 이름을 반영해 `*CommandAdapter`, `*QueryAdapter`, `{행위}CommandAdapter`처럼 분리하고, 여러 outbound port를 한 class에 합치지 않는다.
- PKG-ADAPTER-004: persistence concept package는 개념 단위를 먼저 보존하되, `Entity`, `JpaRepository`, port adapter, mapper가 섞여 파일 탐색성이 떨어지면 concept 하위에 `adapter`, `entity`, `repository`, `mapper` package로 책임을 분리한다.
- PKG-ADAPTER-005: persistence concept의 책임별 하위 package는 파일 수가 6개 이상이거나, entity/repository/adapter 중 둘 이상의 책임에서 복수 파일이 생길 때 우선 검토한다. 파일 수가 적고 책임이 명확하면 flat concept package를 유지할 수 있다.
- PKG-EXTERNAL-001: external adapter는 먼저 provider package로 나눈다.
- PKG-EXTERNAL-002: provider package root는 provider API client, provider config, provider properties, token holder, provider exception, provider error code, port adapter 구현체를 함께 소유한다.
- PKG-EXTERNAL-003: provider DTO가 소수이고 provider 밖으로 노출되지 않으면 provider package root에 둘 수 있다.
- PKG-EXTERNAL-004: provider DTO가 늘어나거나 request/response/failure DTO 책임이 분리되면 `dto` 하위 package로 분리한다.
- PKG-EXTERNAL-005: provider code값이나 mapping enum이 둘 이상이거나 여러 adapter에서 공유되면 `code` 하위 package로 분리한다.
- PKG-EXTERNAL-006: provider별 mock adapter는 같은 provider package 안에 두고 production adapter와 동일한 port contract를 구현한다.
- PKG-EXTERNAL-007: provider package 안의 client, DTO, error code, exception은 application port contract 밖으로 노출하지 않는다.
- PKG-EXTERNAL-008: 하나의 provider package 안에서 업무 능력이 크게 갈라지면 adapter class 이름으로 책임을 드러내고, 파일 탐색이 어려워질 때만 업무 능력 하위 package로 분리한다.
- PKG-SUPPORT-001: support package는 둘 이상의 실행 앱에서 공유되는 web contract와 공통 응답 형식만 소유한다.
- PKG-TEST-001: test package는 production package와 대응되게 둔다.
- PKG-TEST-002: production 파일을 책임별 하위 package로 이동하면 해당 파일의 단위 테스트도 같은 package 구조로 이동한다. package 전체 구조를 검증하는 architecture/structure test는 검증 대상 concept root test package에 둘 수 있다.
- PKG-LEGACY-001: 레거시 코드가 현재 패키지 규칙을 위반하더라도 새 코드는 현재 규칙을 따른다.
- PKG-LEGACY-002: 레거시 예외를 유지해야 하면 적용 범위, 이유, 제거 조건을 TDD 또는 run artifact에 남긴다.

## 우선순위

- PKG-PRIORITY-001: 새 코드 배치는 기존 파일 근처보다 책임 소유 패키지를 우선한다.
- PKG-PRIORITY-002: 모듈 경계와 패키지 편의성이 충돌하면 모듈 경계를 우선한다.
- PKG-PRIORITY-003: 패키지 배치가 레이어 의존 방향과 충돌하면 [ARCHITECTURE_RULES.md](ARCHITECTURE_RULES.md)를 우선한다.
- PKG-PRIORITY-004: 구조 판단이 불명확하면 구현 전에 TDD에서 후보 위치와 trade-off를 명시한다.
<!-- OPENDOCK:END id=files:docs/rules/backend/PACKAGE_STRUCTURE.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/PACKAGE_STRUCTURE.md -->
