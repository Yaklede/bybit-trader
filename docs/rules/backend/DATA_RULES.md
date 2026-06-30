<!-- OPENDOCK:START id=files:docs/rules/backend/DATA_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/DATA_RULES.md -->
# Backend Data Rules

## 목표

백엔드 데이터 저장, 조회, migration, transaction, consistency 규칙이 코드 규모와 관계없이 안정적으로 유지되게 한다.

AI 에이전트는 이 문서를 기준으로 DB/schema, JPA entity, repository, QueryDSL, Flyway, transaction, pagination, seed data 변경을 판단한다.

## 적용 범위

- `internal/persistent`, DB migration, entity, repository, query adapter, transaction boundary 변경에 적용한다.
- MySQL, H2 test runtime, Flyway, JPA, QueryDSL 기반 코드에 적용한다.
- transaction boundary의 계층 책임은 [ARCHITECTURE_RULES.md](ARCHITECTURE_RULES.md), 테스트는 [TESTING_RULES.md](TESTING_RULES.md)를 따른다.

## 정의

- Persistence adapter: application outbound port를 DB 기술로 구현하는 adapter.
- Entity: DB table과 persistence mapping을 표현하는 타입.
- Domain model: business invariant와 행위를 표현하는 core/domain 타입.
- Migration: DB schema 또는 seed 구조 변경을 순서 있게 적용하는 script.
- Consistency boundary: 하나의 작업에서 반드시 함께 성공하거나 실패해야 하는 데이터 변경 범위.
- Query model: 조회 조건, cursor, page, sorting을 표현하는 application 또는 adapter contract.
- Materialized stat: 요청 처리 원장 table에서 계산한 값을 조회 성능을 위해 별도 table에 저장한 통계 row.
- Cumulative stat snapshot: 특정 기준일의 누적 방문자 수를 날짜별 row로 저장해 최신 전체 방문자 수 조회와 날짜별 누적 추이를 함께 지원하는 materialized stat.

## 규칙

- DATA-NAME-001: table, index, unique constraint, foreign key, migration 객체명은 호출 주체나 실행 채널보다 저장 데이터가 표현하는 개념 또는 리소스 이름을 우선한다.
- DATA-NAME-002: 특정 실행 단위 전용 운영 데이터가 아니라면 persistence 객체명에 소비자나 배포 모듈에 종속된 prefix를 넣지 않는다.
- DATA-ADAPTER-001: persistence adapter는 application outbound port를 구현한다.
- DATA-ADAPTER-002: application layer가 JPA repository, EntityManager, QueryDSL type을 직접 참조하지 않는다.
- DATA-ENTITY-001: entity는 persistence mapping을 소유하고 domain invariant의 원천이 되지 않는다.
- DATA-ENTITY-002: domain model과 entity는 mapper로 명시적으로 변환한다.
- DATA-ENTITY-003: entity 변경이 API response나 application result 변경으로 바로 전파되지 않게 한다.
- DATA-MIG-001: schema 변경은 migration으로 관리한다.
- DATA-MIG-002: migration은 이미 배포된 환경에서 재실행 가능성과 순서를 고려한다.
- DATA-MIG-003: destructive migration은 backup, rollback, data migration 전략을 TDD 또는 run artifact에 남긴다.
- DATA-TX-001: transaction boundary는 application 유스케이스 단위에서 결정한다.
- DATA-TX-002: persistence adapter는 transaction boundary를 임의로 넓히지 않는다.
- DATA-TX-003: 외부 API 호출, 파일 I/O, long-running work는 DB transaction 내부에서 수행하지 않는다.
- DATA-CONSIST-001: 하나의 유스케이스에서 여러 aggregate 또는 table을 변경하면 consistency boundary를 명시한다.
- DATA-CONSIST-002: eventual consistency, retry, idempotency가 필요한 작업은 실패 재처리 정책을 명시한다.
- DATA-CONSIST-003: 요청 path에서 정확한 원장 row와 통계 counter row를 동시에 갱신해 hot row나 응답 지연을 만들 수 있으면, 원장 row만 동기 저장하고 통계 row는 batch materialization으로 분리한다.
- DATA-CONSIST-004: batch materialized stat은 재실행해도 중복 누적되지 않도록 target date 기준 upsert 또는 원장/stat 합산 재계산 방식으로 설계한다.
- DATA-CONSIST-005: Spring Batch Tasklet에서 materialized stat 저장을 application command UseCase에 위임하면 application service가 transaction 안에서 일별 stat 저장, 누적 stat 재계산, 누적 stat snapshot 저장 순서를 소유한다.
- DATA-CONSIST-006: 방문자 누적 통계가 날짜별 추이 요구로 확장될 수 있으면 singleton total row보다 snapshot date를 key로 하는 cumulative stat snapshot을 우선한다.
- DATA-QUERY-001: 목록 조회는 sorting 기준과 pagination 기준을 안정적으로 둔다.
- DATA-QUERY-002: cursor pagination은 cursor 생성 기준과 tie-breaker를 명시한다.
- DATA-QUERY-003: N+1, full scan, unbounded result를 새 코드에 도입하지 않는다.
- DATA-QUERY-004: command flow에서 다건 대상을 조회할 때는 limit 또는 cursor, batch size, stable ordering을 가진 command port로 설계한다.
- DATA-QUERY-005: `find...Before(...): List<Id>`처럼 조건에 맞는 전체 결과를 한 번에 반환할 수 있는 command port나 repository method를 새 코드에 도입하지 않는다.
- DATA-QUERY-006: 다건 command는 batch 단위 transaction, retry/resume 기준, 부분 실패 처리, 처리 건수 의미를 TDD 또는 run artifact에 남긴다.
- DATA-QUERY-007: persistence adapter는 다건 command 대상을 in-memory full scan, filter, map으로 메모리에 적재하지 않고 DB query 단계에서 범위와 정렬을 제한한다.
- DATA-QUERYDSL-001: 동적 조건 조립은 `BooleanBuilder`보다 nullable `BooleanExpression` 반환 방식을 우선한다.
- DATA-QUERYDSL-002: 단순 동등 비교, 단일 필드 조건, 단순 null-check 조건은 `where` 절에 직접 작성한다.
- DATA-QUERYDSL-003: 날짜 범위, OR 조합, 여러 필드 묶음처럼 의미 단위가 큰 조건만 private method로 추출한다.
- DATA-QUERYDSL-004: QueryDSL `where()`가 null 조건을 무시하는 동작을 활용해 선택 조건을 표현한다.
- DATA-QUERYDSL-005: 조건, join, orderBy는 기존 쿼리 의미를 보존하는 순서를 유지하고 가독성 목적만으로 임의 재배열하지 않는다.
- DATA-QUERYDSL-006: 동일한 join 조합이 여러 쿼리에서 반복될 때만 extension function 또는 private method로 추출한다.
- DATA-QUERYDSL-007: 단일 쿼리에서만 쓰는 join은 inline으로 유지한다.
- DATA-QUERYDSL-008: private condition method 이름은 조건의 구현 방식보다 비즈니스 의미를 드러낸다.
- DATA-PAGE-001: fetch join과 pagination을 함께 사용하지 않는다.
- DATA-PAGE-002: 연관 데이터가 필요한 pagination query는 2-step 조회를 우선한다.
- DATA-PAGE-003: 2-step pagination의 Step 1은 조건에 맞는 ID만 offset/limit으로 조회한다.
- DATA-PAGE-004: 2-step pagination의 Step 2는 Step 1에서 조회한 ID 기준으로 연관 데이터를 조회한다.
- DATA-PAGE-005: count query는 content query와 분리하고, count에 불필요한 fetch join이나 projection을 포함하지 않는다.
- DATA-PAGE-006: 2-step pagination의 Step 2 결과는 Step 1의 ID 순서를 보존하도록 정렬하거나 재정렬한다.
- DATA-PROJECTION-001: `Tuple.get()`은 타입 안정성이 낮으므로 기본 projection 방식으로 사용하지 않는다.
- DATA-PROJECTION-002: 조회 결과 shape가 명확하면 DTO projection, constructor projection, 또는 명시적 mapper를 우선한다.
- DATA-SEED-001: local seed data는 운영 데이터 초기화와 분리한다.
- DATA-SEED-002: seed data는 테스트 성공을 숨기는 전역 전제 조건이 되어서는 안 된다.
- DATA-TEST-001: query, mapping, migration 영향이 있는 변경은 persistence test 또는 integration test를 포함한다.

## 우선순위

- DATA-PRIORITY-001: 데이터 정합성은 빠른 구현과 API 편의보다 우선한다.
- DATA-PRIORITY-002: domain model과 entity 경계는 중복 코드 감소보다 우선한다.
- DATA-PRIORITY-003: migration 안전성은 개발 환경 편의보다 우선한다.
- DATA-PRIORITY-004: 성능 위험이 있는 query는 기능 동작만으로 완료하지 않는다.
<!-- OPENDOCK:END id=files:docs/rules/backend/DATA_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/DATA_RULES.md -->
