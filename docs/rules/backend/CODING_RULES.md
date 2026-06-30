<!-- OPENDOCK:START id=files:docs/rules/backend/CODING_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/CODING_RULES.md -->
# Backend Coding Rules

## 목표

백엔드 코드 작성 방식이 모듈과 작업자에 따라 흔들리지 않게 한다.

AI 에이전트는 이 문서를 기준으로 naming, DTO, mapper, exception, validation, Kotlin style, config 작성 방식을 판단한다.

## 적용 범위

- Kotlin/Spring backend production code와 test code에 적용한다.
- Controller, UseCase, service, domain model, adapter, entity, DTO, mapper, exception, config 변경에 적용한다.
- 테스트 설계는 [TESTING_RULES.md](TESTING_RULES.md), API 계약은 [API_RULES.md](API_RULES.md), 보안 세부사항은 [SECURITY_RULES.md](SECURITY_RULES.md)를 따른다.

## 정의

- DTO: 계층 또는 외부 계약 경계를 넘기 위해 사용하는 데이터 전달 타입.
- Command: 상태 변경 유스케이스 입력을 표현하는 application DTO.
- Query: 조회 유스케이스 입력 조건을 표현하는 application DTO.
- Result: 유스케이스 출력 또는 port 출력 값을 표현하는 application DTO.
- Mapper: 계층 간 타입 변환을 명시적으로 소유하는 코드.
- ErrorCode: 실패를 안정적으로 분류하기 위한 machine-readable code.
- DomainErrorCode: domain layer에서 발생한 domain invariant, 상태 전이, 도메인 규칙 위반을 표현하는 domain 소유 error code.
- ApplicationErrorCode: application layer에서 발생한 유스케이스 전제 조건, 권한, 리소스 없음, port/adapter 실패를 표현하는 application 소유 error code.
- Validation: 입력 형식, 존재 여부, 권한, domain invariant를 각각 맞는 계층에서 확인하는 행위.

## 규칙

- CODE-NAME-001: 클래스 이름은 책임을 드러내는 suffix를 사용한다.
- CODE-NAME-002: `Controller`, `UseCase`, `Service`, `Port`, `Adapter`, `Repository`, `Mapper`, `Config`, `Properties`, `Exception`, `ErrorCode` suffix를 책임과 다르게 사용하지 않는다.
- CODE-NAME-003: 이름만 분리하고 같은 책임을 중복 구현하지 않는다.
- CODE-NAME-004: `Command`, `Query`, `CommandUseCase`, `QueryUseCase`, `CommandService`, `QueryService` suffix는 실제 CQRS 책임과 일치할 때만 사용한다.
- CODE-NAME-005: `Service`, `Strategy`, `Policy`, `DomainService` suffix는 실제 application/domain 책임과 일치할 때만 사용한다.
- CODE-NAME-006: package와 모듈이 이미 업무 맥락을 제공하면 class 이름에 같은 맥락의 접두사를 반복하지 않는다. 단, 같은 패키지나 모듈 안에서 서로 다른 실행 앱, 외부 계약, 저장소 모델을 명확히 구분해야 하는 타입은 예외로 둘 수 있다.
- CODE-NAME-007: application inbound DTO의 top-level 이름은 업무 행위와 도메인 개념을 드러내고, 입력 성격은 nested `Command` 또는 `Query`로 표현한다. top-level DTO 이름에 `Command`/`Query` suffix를 중복하지 않는다.
- CODE-NAME-008: core application의 UseCase, service, command/result DTO 이름은 호출 주체, delivery channel, runtime app이 아니라 수행하는 사용자 행위, 업무 시나리오, 도메인 책임을 기준으로 짓는다. delivery adapter, 앱별 config, security entrypoint처럼 channel이나 runtime app 자체가 책임 경계인 타입은 예외로 둘 수 있다.
- CODE-NAME-009: 특정 도메인 개념의 상태 변경 transaction boundary를 소유하는 application service는 `{Concept}CommandService`처럼 도메인 개념과 command 책임을 이름에 드러낸다. `Metadata`, `Manager`, `Handler`처럼 저장 형태나 구현 세부사항을 강조하는 이름은 해당 구현 세부사항이 실제 책임 경계일 때만 사용한다.
- CODE-NAME-010: domain concept 하위 값 객체 이름은 소유 concept의 공통 언어를 우선한다. 특정 업무 시나리오나 상위 resource 종속 규칙은 값 객체 타입명에 섞기보다 static factory, reconstitution method, 행위 method 이름으로 드러낸다.
- CODE-DTO-001: DTO는 사용하는 경계별로 분리한다.
- CODE-DTO-002: HTTP request/response DTO를 application port나 domain model로 전달하지 않는다.
- CODE-DTO-003: application command/result DTO는 HTTP, session, DB, provider SDK 타입을 포함하지 않는다.
- CODE-DTO-004: 외부 provider DTO와 persistence entity는 adapter 밖으로 노출하지 않는다.
- CODE-DTO-005: Command DTO는 상태 변경 입력만 표현하고 Query DTO는 조회 조건만 표현한다.
- CODE-MAP-001: 계층 경계 변환은 mapper 또는 명시적 factory function이 소유한다.
- CODE-MAP-002: controller, application service, adapter에 복잡한 변환 로직을 흩뿌리지 않는다.
- CODE-MAP-003: mapper는 정책 판단을 하지 않고 구조 변환만 수행한다.
- CODE-ERR-001: domain model, value object, domain service에서 발생한 domain invariant, 상태 전이, 도메인 규칙 위반은 domain concept가 소유한 `DomainErrorCode`와 `DomainException`으로 표현한다.
- CODE-ERR-002: application service에서 발생한 유스케이스 전제 조건, 권한, 리소스 없음, orchestration 실패는 application concept가 소유한 `ApplicationErrorCode`와 `ApplicationException`으로 표현한다.
- CODE-ERR-003: adapter 실패는 `ApplicationException`과 해당 개념의 `ErrorCode`로 번역한다.
- CODE-ERR-004: 일반 `RuntimeException`, 문자열 기반 예외, catch 후 무시를 새 코드에 도입하지 않는다.
- CODE-ERR-005: Application layer는 `DomainException`을 기본적으로 `ApplicationException`으로 변환하지 않는다. 유스케이스 또는 API 계약상 실패 의미를 바꿔야 할 때만 특정 `DomainErrorCode`를 명시적으로 잡아 application 실패로 변환한다.
- CODE-ERR-006: Delivery adapter의 global exception handler는 `DomainException`과 `ApplicationException`을 각각 핸들링하고, 각 exception이 가진 `ErrorCode`의 code, message, error type을 API error response로 변환한다.
- CODE-VAL-001: request shape validation은 delivery adapter에서 처리한다.
- CODE-VAL-002: 존재 여부, 권한, 유스케이스 전제 조건은 application에서 처리한다.
- CODE-VAL-003: 불변식과 상태 전이 검증은 domain model에서 처리한다.
- CODE-VAL-004: Validator가 데이터 조회, 상태 변경, 외부 호출을 동시에 수행하지 않는다.
- CODE-VAL-005: private constructor와 companion/static factory를 가진 domain concept은 `init`에 생성 경로별 검증을 숨기지 않고, 각 factory 또는 reconstitution method에서 목적에 맞는 validation function을 명시적으로 호출한다.
- CODE-KOTLIN-001: nullable type은 실제로 null이 가능한 경우에만 사용한다.
- CODE-KOTLIN-002: `!!`는 production code에 사용하지 않는다.
- CODE-KOTLIN-003: data class는 값 전달 목적에 사용하고 행위와 불변식이 필요한 domain model에는 신중하게 사용한다.
- CODE-KOTLIN-004: 긴 함수, 깊은 중첩, 숨은 side effect는 작은 private function 또는 domain/application 책임으로 분리한다.
- CODE-CONFIG-001: secret, token, password, provider client secret, 운영 URL을 코드에 하드코딩하지 않는다.
- CODE-CONFIG-002: profile별 값은 configuration property 또는 environment로 주입한다.
- CODE-CONFIG-003: 파일 크기 제한, 허용 타입, 상태 전이 기준처럼 제품이 보장해야 하는 도메인 정책값은 profile별 environment로 분리하지 않는다. 런타임 위치, provider endpoint, credential, 인프라 처리 한계처럼 배포 환경에 따라 달라지는 값만 configuration property로 둔다.
- CODE-COMMENT-001: 주석은 복잡한 의사결정 이유를 설명할 때만 사용한다.
- CODE-COMMENT-002: 코드가 말할 수 있는 내용을 반복하는 주석을 추가하지 않는다.

## 우선순위

- CODE-PRIORITY-001: 계층 경계를 지키는 타입 분리는 파일 수를 줄이는 편의보다 우선한다.
- CODE-PRIORITY-002: 명확한 실패 표현은 빠른 예외 throw보다 우선한다.
- CODE-PRIORITY-003: 기존 코드 스타일과 RULES 문서가 충돌하면 RULES 문서를 우선한다.
- CODE-PRIORITY-004: coding rule이 architecture rule과 충돌하면 architecture rule을 우선한다.
<!-- OPENDOCK:END id=files:docs/rules/backend/CODING_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/CODING_RULES.md -->
