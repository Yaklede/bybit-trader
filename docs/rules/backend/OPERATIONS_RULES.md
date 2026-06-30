<!-- OPENDOCK:START id=files:docs/rules/backend/OPERATIONS_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/OPERATIONS_RULES.md -->
# Backend Operations Rules

## 목표

백엔드 코드가 로컬에서만 동작하는 수준에 머물지 않고 운영, 배포, 관측, 장애 대응을 고려한 형태로 유지되게 한다.

AI 에이전트는 이 문서를 기준으로 profile, configuration, logging, health check, deploy artifact, migration execution, runtime dependency 변경을 판단한다.

## 적용 범위

- Spring profile, `application-*.yml`, deployment stack, Docker compose, health endpoint, logging, actuator, build artifact, runtime dependency 변경에 적용한다.
- CI workflow, bootJar, 운영 환경 변수, profile-specific config에 적용한다.
- secret 처리는 [SECURITY_RULES.md](SECURITY_RULES.md), DB migration은 [DATA_RULES.md](DATA_RULES.md), 테스트 검증은 [TESTING_RULES.md](TESTING_RULES.md)를 따른다.

## 정의

- Profile: local, prod, migration-local처럼 runtime configuration을 분리하는 실행 환경 이름.
- Runtime dependency: DB, Redis, external API, storage, message broker처럼 앱 실행에 필요한 외부 자원.
- Health endpoint: 배포와 운영에서 앱 생존 여부를 확인하는 API.
- Operational log: 장애 분석, 추적, 감사에 필요한 구조화된 로그.
- Deploy artifact: bootJar, Docker image, stack file처럼 배포에 사용되는 산출물.
- Build module: Gradle `settings.gradle.kts`에 등록되고 독립 build 설정을 가지는 backend module.
- Executable module: Spring Boot application, batch, worker처럼 실행 산출물을 만드는 module.
- Library module: 다른 module이 참조하지만 직접 실행 산출물을 만들지 않는 module.

## 규칙

- OPS-PROFILE-001: profile별 설정은 명시적인 `application-{profile}.yml` 또는 imported config로 관리한다.
- OPS-PROFILE-002: local, migration, prod 설정의 차이를 코드 분기로 숨기지 않는다.
- OPS-CONFIG-001: 운영 값은 environment나 deployment config에서 주입한다.
- OPS-CONFIG-002: 기본값이 보안 또는 데이터 손실 위험을 만들면 기본값을 두지 않는다.
- OPS-CONFIG-003: 새 runtime dependency를 추가하면 local 실행, test, prod 배포 설정 영향을 함께 검토한다.
- OPS-LOG-001: 상태 변경, 보안 실패, 외부 연동 실패, batch 실패는 추적 가능한 로그를 남긴다.
- OPS-LOG-002: 로그에는 correlation에 필요한 식별자를 남기되 민감정보는 남기지 않는다.
- OPS-LOG-003: 정상 제어 흐름을 error log로 남기지 않는다.
- OPS-LOG-004: 다건 상태 변경 command, batch, scheduler는 처리 대상 범위, 처리 건수, 실패 건수, 재처리 key를 로그 또는 metric으로 추적할 수 있게 한다.
- OPS-LOG-005: 로그 이벤트 문장은 한글로 작성하고, 각 이벤트와 추적 필드는 `[이벤트] [key=value]` 형태의 대괄호 구획으로 분리한다.
- OPS-LOG-006: 흐름 추적용 로그는 결과 이벤트를 먼저 쓰고, 이어서 correlation id, 리소스 key, 상태 또는 실패 원인 순서로 필드를 안정적으로 배치한다.
- OPS-LOG-007: API 요청 처리 중 발생한 `ApplicationException`, `DomainException`, 알 수 없는 `Exception`의 공통 실패 로그는 각 실행 앱의 GlobalExceptionHandler에서 한 번만 남긴다.
- OPS-LOG-008: GlobalExceptionHandler의 공통 API 실패 로그는 request body나 민감정보를 남기지 않고, `traceId`, HTTP method, request path, `failureType`처럼 요청 추적에 필요한 필드를 안정적으로 포함한다.
- OPS-HEALTH-001: 실행 앱은 운영자가 확인할 수 있는 health endpoint를 가진다.
- OPS-HEALTH-002: health endpoint는 인증 정책과 운영 노출 범위를 명확히 한다.
- OPS-DEPLOY-001: 실행 앱의 bootJar artifact 이름은 배포 설정과 일치해야 한다.
- OPS-DEPLOY-002: deployment stack 변경은 해당 앱의 port, env, secret, volume, network 영향을 함께 검토한다.
- OPS-BUILD-001: 새 backend module은 `settings.gradle.kts`와 해당 module의 `build.gradle.kts`에 명시한다.
- OPS-BUILD-002: Spring Boot plugin은 executable module에만 적용한다.
- OPS-BUILD-003: library module은 bootJar 산출물을 만들지 않는다.
- OPS-BUILD-004: bootJar archiveFileName은 deployment stack과 CI artifact 경로가 기대하는 이름과 일치해야 한다.
- OPS-MIGRATION-001: migration 실행 profile과 일반 application profile을 혼동하지 않는다.
- OPS-MIGRATION-002: 운영 migration은 실패 시 복구 전략을 고려한다.
- OPS-CI-001: build workflow가 깨지는 dependency, plugin, module 변경을 하지 않는다.
- OPS-CI-002: 새 모듈이나 runtime dependency를 추가하면 CI/build 검증 범위를 갱신한다.

## 우선순위

- OPS-PRIORITY-001: 운영 안전성은 로컬 편의보다 우선한다.
- OPS-PRIORITY-002: secret 보호는 logging과 debugging 편의보다 우선한다.
- OPS-PRIORITY-003: 배포 산출물과 runtime config가 맞지 않으면 기능 구현 완료로 보지 않는다.
- OPS-PRIORITY-004: 운영 영향이 불명확하면 변경 범위와 필요한 확인 사항을 사용자에게 보고한다.
<!-- OPENDOCK:END id=files:docs/rules/backend/OPERATIONS_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/OPERATIONS_RULES.md -->
