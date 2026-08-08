# 거래량 확인형 추세 전략 실거래 실행 기술 설계

> 작성일: 2026-08-07
> 상태: 승인 기반 런타임·체결/계좌/거래내역 projection·위험 게이트·대시보드·읽기 전용 거래소 계약 점검 완료, 전진 승인 대기
> 대상 모듈: `bot-engine`, `bot-exchange-bybit`, `bot-ledger`, `bot-app`

## 1. 설계 배경 및 목적

### 1.1 배경

`volume-confirmed-trend-ensemble-v1`은 인과적 H4 계산 코어, Binance USD-M 외부 이력,
비용 스트레스, Node/Kotlin 계산 패리티, 역사 어댑터/영속 Shadow 패리티를 통과했다.
현재 운영 런타임은 public-data Shadow까지만 활성화할 수 있다. 개인 주문 실행 코어와 Bybit 대사
adapter는 구현됐지만 기본 `NOT_APPROVED` 영수증과 런타임 비활성 경계로 물리적으로 차단돼 있다.

기존 `ExchangeExecutionService`는 M5 신호, 구조적 손절, 실제 체결 후 TP/SL 재설정,
최대 보유 시간을 전제로 한다. 반면 새 후보는 계좌 equity의 `0.65`만 목표 명목가로 사용하고,
반올림 후 `0.85`를 넘지 않으며, 고정 TP/SL 없이 확인된 반대 H4 방향까지 포지션을 유지한다.
기존 실행기를 재사용하면 백테스트와 다른 전략이 되므로 별도 목표 포지션 실행 계약이 필요하다.

### 1.2 설계 목표

1. **승인 전 주문 불가능**: 90일 연속 Shadow와 사람 승인을 모두 증명하는 별도 승인 영수증이
   없으면 서비스 구성 단계에서 실거래 경로를 생성하지 않는다.
2. **실행 계약 동일화**: 닫힌 H4 결정, 다음 H4 실행, 수수료·슬리피지, 수량 반올림,
   반대 방향까지 보유하는 규칙을 역사·Shadow·Live에서 공유한다.
3. **목표 포지션 정합성**: 거래소 실제 포지션을 기준으로 `FLAT -> ENTRY -> OPEN -> EXIT -> FLAT`
   순서를 지키고, 반전은 종료 체결 확인 전 신규 진입을 금지한다.
4. **복구 가능성**: 주문 승인 직후 프로세스가 종료돼도 거래소 조회와 append-only 원장으로
   마지막 의도를 복원하고 중복 주문을 막는다.
5. **관측 가능성**: 결정, 주문 의도, 체결, 수수료, 펀딩, 포지션, equity, 추적 오차와 모든
   차단 사유를 대시보드와 알림에서 설명할 수 있게 저장한다.

### 1.3 설계 비목표

- Shadow 승인 전에 실거래 플래그나 승인 JSON을 활성화하지 않는다.
- v1의 EMA, 거래량, 비용, 노출 파라미터를 실거래 구현 과정에서 튜닝하지 않는다.
- 기존 `absa_final_us_v1` 자동 루프를 다시 활성화하지 않는다.
- H4 전략에 존재하지 않는 고정 TP, 고정 SL, 트레일링, 시간 종료를 몰래 추가하지 않는다.
- 여러 종목, hedge mode, 여러 동시 포지션은 지원하지 않는다.

### 1.4 기술적 제약사항

- 대상은 `BTCUSDT` Bybit linear perpetual과 Unified account다.
- one-way position mode와 cross margin을 요구한다. hedge mode 또는 격리 margin이면 fail closed한다.
- 거래소 leverage는 `1`로 고정한다. 전략의 실질 명목 노출이 equity보다 작으므로 기존 운영값
  `15`를 상속하지 않는다.
- `minimumQuantityBtc=0.001`, `quantityStepBtc=0.001`을 사용하되 시작 시 거래소 instrument
  metadata와 일치하는지 확인한다.
- 자동화된 계좌 손절이나 보호 주문을 추가하면 전략 변경이므로 역사 재평가와 새 forward session이
  필요하다. 운영자의 `SAFE_STOP`과 `FLATTEN`은 전략 외 비상 조치로 유지한다.

## 2. 현행 시스템 분석

### 2.1 관련 구조

```text
VolumeConfirmedTrendEvaluator
  -> VolumeConfirmedTrendExecutionModel
     -> Historical simulator
     -> Persistent Shadow service

ExchangeExecutionService
  -> ExchangeExecutionGateway
     -> BybitPrivateClient
  -> execution lifecycle / fill / account ledger
```

계산 코어와 거래소 port는 존재하지만, H4 목표 포지션을 개인 주문으로 변환하는 application service가
없다. `VolumeConfirmedTrendApprovalService`는 모든 정량 게이트가 통과해도
`liveExecutionAllowed=false`를 반환하므로 자동 승격은 현재 구조상 불가능하다.

### 2.2 현재 처리 흐름

```text
M15 동기화 -> 완결 H4 집계 -> EMA/거래량 결정
  -> 다음 H4 시각 ticker를 참조한 가상 종료/진입
  -> volumeConfirmedTrendShadowStates + events 원자 저장
  -> approval report
```

### 2.3 현행 스키마

| 테이블 | 현재 역할 | 변경 필요성 |
|---|---|---|
| `volumeConfirmedTrendShadowStates` | 전진 Shadow checkpoint | 읽기 전용 승인 증거로 재사용 |
| `volumeConfirmedTrendShadowEvents` | 결정·가상 체결·비용 이벤트 | 승인 session fingerprint 계산에 사용 |
| `executionLifecycleEvents` | 기존 M5 개인 주문 상태 | 전략 구분과 H4 반전 상태를 표현하기 어려움 |
| `executionFillEvents` | 실제 `execId` 기준 체결 원장 | H4 실거래도 재사용 |
| `executionAccountSnapshots` | 실제 계좌 equity | 수량 계산과 추적 오차에 재사용 |

H4 실거래는 독립된 단일-row checkpoint와 append-only event table을 추가한다. 기존 lifecycle을
억지로 확장하면 고정 TP/SL 상태와 목표 포지션 상태가 섞이므로 사용하지 않는다.

## 3. 아키텍처 설계

### 3.1 계층별 책임

| 계층 | 구성 요소 | 책임 | 근거 |
|---|---|---|---|
| Domain | `VolumeConfirmedTrendTargetPlanner` | 실제 equity·가격·현재 포지션을 결정적 목표 수량과 단계별 intent로 변환 | 거래소와 DB를 모르는 공통 규칙 |
| Application | `VolumeConfirmedTrendLiveService` | 승인, 데이터 최신성, 대사, 상태 전이, port 호출 orchestration | 한 번의 평가를 직렬화하는 UseCase |
| Storage port | `VolumeConfirmedTrendLiveStore` | checkpoint와 append-only event의 원자 commit | 재시작과 멱등성 |
| External port | `ExchangeExecutionGateway` 확장 | 계정 모드·margin·instrument 규칙 조회, 주문·포지션·체결 조회 | Bybit 세부사항 격리 |
| Adapter | `BybitPrivateClient` | V5 응답을 공통 port 모델로 변환 | provider code를 domain에 노출하지 않음 |
| App | 설정·loop·API·알림 | 명시적 활성화와 상태 노출 | transport와 운영 wiring만 소유 |

### 3.2 처리 흐름

```text
H4 boundary + delay
  -> approval receipt와 현재 approval report 대조
  -> public M15/H4 데이터 최신성 확인
  -> private wallet/position/exact-order/execution 대사
  -> TargetPlanner.plan(...)
     -> NOOP | CLOSE | OPEN | HALT
  -> intent + checkpoint 원자 기록
  -> 결정적 clientOrderId로 주문 제출
  -> private stream/REST로 체결 확인
  -> 실제 position read-back
  -> 다음 상태 commit
```

반전은 한 호출에서 두 주문을 연속 제출하지 않는다. 첫 호출은 reduce-only 종료만 제출하고,
종료가 거래소에서 확인된 다음 호출이 저장된 pending target을 신규 진입으로 제출한다.

### 3.3 설계 대안

| 대안 | 장점 | 단점 | 판정 |
|---|---|---|---|
| 기존 M5 실행기에 strategy만 교체 | 구현량 감소 | TP/SL·최대 보유·위험 수량 계약이 달라 패리티 파괴 | 기각 |
| 한 주문으로 반대 수량까지 제출 | 빠른 반전 | partial fill 시 의도보다 큰/작은 포지션, reduce-only 불가 | 기각 |
| 종료 확인 후 별도 신규 진입 | 상태가 명확하고 재시작 복구 가능 | 몇 초의 추적 오차 발생 | 채택 |
| forward policy의 boolean을 직접 변경 | 파일 하나로 활성화 | 사전 고정 정책 hash를 변경하고 승인 주체가 불명확 | 기각 |
| 별도 live approval receipt | 정책 불변, 사람 승인과 session 증거 추적 가능 | 승인 artifact 하나 추가 | 채택 |

## 4. 도메인 모델 설계

### 4.1 애그리거트

`protocolId + symbol`을 하나의 Live aggregate로 둔다. 한 계정에서 BTCUSDT H4 전략은 동시에
하나만 존재한다. 거래소 포지션이 둘 이상이거나 hedge mode이면 aggregate는 `HALTED`다.

### 4.2 상태 머신

```text
DISABLED
FLAT
ENTRY_INTENT_RECORDED
ENTRY_SUBMITTED
ENTRY_NOT_FILLED
OPEN
EXIT_INTENT_RECORDED
EXIT_SUBMITTED
EXIT_NOT_FILLED
HALTED
```

주요 불변식:

- receipt의 protocol/policy/session/hash가 현재 증거와 모두 일치해야 한다.
- `ENTRY_SUBMITTED`는 거래소가 `FLAT`임을 확인한 경우에만 가능하다.
- `EXIT_SUBMITTED`에서는 신규 진입 주문을 만들지 않는다.
- `clientOrderId`는 protocol hash, H4 execution time, side, phase에서 결정적으로 만든다.
- 목표 수량은 `floor(equity * 0.65 / adverseReferencePrice, 0.001)`이고 반올림 후 명목 노출이
  equity의 `0.85`를 넘으면 주문하지 않는다.
- 최소 수량이 상한을 넘으면 올림하지 않고 `NO_TRADE`를 기록한다.
- 같은 방향 포지션은 다음 반대 전환까지 재조정하지 않는다. 중간 rebalance는 백테스트에 없다.
- IOC가 미체결 취소되면 해당 H4 결정을 소비하고 같은 client ID 또는 같은 결정으로 재주문하지 않는다.
- exact-order 상태를 실시간 endpoint와 주문 이력 모두에서 확인할 수 없으면 재주문하지 않고 `HALTED`한다.
- USDT wallet 변화와 transaction log를 대사할 수 없거나 unitized NAV가 준비되지 않으면 신규 진입을
  fail closed한다. 기존 포지션의 reduce-only 종료는 위험 차단 중에도 허용한다.
- unitized NAV 최고점 대비 낙폭이 동결 protocol의 최대 허용 MDD `35%` 이상이면 신규 진입을 차단한다.
- 일 손실과 연속 손실 제한은 동결 전략의 역사 실행 계약에 없으므로 v1에 임의로 추가하지 않는다.
  추가하려면 새 protocol/version으로 역사·외부·forward 검증을 다시 수행한다.

### 4.3 승인 영수증

```text
schemaVersion
approvalId
protocolId / protocolSha256
policyId / policySha256
shadowSessionId / shadowEvidenceSha256
approvalReportSha256
approvedAt / approvedBy
liveExecutionAllowed
```

저장소에는 기본 `NOT_APPROVED` 영수증만 둔다. 실제 승인 영수증은 90일 gate 통과 후 별도 human
review와 커밋으로 생성하며 자동 생성 API를 제공하지 않는다.

### 4.4 Live 원장

```sql
CREATE TABLE volumeConfirmedTrendLiveStates (... PRIMARY KEY (protocol_id, symbol));
CREATE TABLE volumeConfirmedTrendLiveEvents (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  decision_key TEXT NOT NULL,
  event_type TEXT NOT NULL,
  exchange_order_id TEXT,
  client_order_id TEXT,
  payload TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);
```

체결 상세와 account snapshot은 기존 공통 append-only 원장을 참조한다. Live checkpoint에는 원문
체결 목록을 복제하지 않고 마지막 처리 `executionId`, pending target과 상태 fingerprint만 저장한다.

## 5. 트랜잭션 및 정합성

| 연산 | 경계 | 정합성 | 사유 |
|---|---|---|---|
| intent 생성 | state read + event insert + state upsert | SQLite transaction | 주문 전 의도를 반드시 남김 |
| 주문 제출 | 외부 호출 | transaction 밖 | DB lock 중 네트워크 호출 금지 |
| ack 기록 | event insert + state upsert | SQLite transaction | 재시작 시 제출 결과 복원 |
| 체결 반영 | fill dedupe + event + state | SQLite transaction | 수수료·수량 중복 방지 |
| 계좌 대사 | snapshot/transaction append 후 상태 판단 | 최종 일관성 | 거래소가 source of truth |

DB intent 기록 후 주문 호출이 실패하면 결정적 client ID로 거래소 상태를 먼저 조회한다. 거래소가 주문을
확인하지 못해도 자동 재주문하지 않고 제한 시간 뒤 `HALTED`한다. 주문 ack 뒤 DB 쓰기가 실패하면 거래소의
exact-order, order history, execution, position 조회로 복구한다. 미체결 취소는 해당 H4 결정을 소비하며,
로컬 상태만 보고 같은 주문이나 반대 주문을 만들지 않는다.

## 6. 예외 및 실패 처리

| 코드 | 조건 | 처리 |
|---|---|---|
| `TREND_LIVE_NOT_APPROVED` | 영수증 없음/false/hash 불일치 | 신규 노출 차단, 기존 주문·포지션 안전 복구 |
| `APPROVAL_REPORT_UNAVAILABLE` | 현재 승인 보고서를 계산할 수 없음 | 신규 노출 차단, 소유 포지션 reduce-only 종료 |
| `TREND_APPROVAL_REVOKED_POSITION_OWNERSHIP_UNCONFIRMED` | 승인 상실 시 거래소 포지션 소유권 불일치 | 자동 종료 금지, `HALTED`, 사람 확인 |
| `TREND_APPROVAL_REVOKED_EXIT_PRICE_UNAVAILABLE` | 소유 포지션의 유효 mark/reference 가격 없음 | 자동 종료 금지, `HALTED`, 사람 확인 |
| `TREND_SHADOW_GATE_NOT_READY` | 현재 report가 human review 전 | 주문 경로 구성 금지 |
| `TREND_ACCOUNT_MODE_MISMATCH` | hedge/isolated/비 Unified | `HALTED`, Discord 긴급 알림 |
| `TREND_EXPOSURE_LIMIT_EXCEEDED` | 수량 반올림 후 0.85 초과 | 주문 없음 |
| `TREND_POSITION_MISMATCH` | 로컬과 거래소 방향/수량 불일치 | `HALTED`, 신규 진입 차단 |
| `TREND_EXIT_PENDING` | 종료 미확정 | 신규 진입 차단, 대사 반복 |
| `TREND_DATA_STALE` | H4 또는 ticker 지연 | 해당 전환 폐기, 다음 신호까지 대기 |
| `TREND_ORDER_STATE_UNKNOWN` | ack/stream/REST 불일치 | `HALTED`, 자동 재주문 금지 |
| `TREND_LEDGER_WRITE_FAILED` | intent/ack/fill 저장 실패 | loop 중단, 사람 확인 전 재개 금지 |
| `RISK_NAV_BASELINE_PENDING` | 입출금을 분리한 unitized NAV 기준점 미완성 | 신규 진입 보류, 계좌 관측·종료 허용 |
| `ACCOUNT_DRAWDOWN_LIMIT_REACHED` | unitized NAV MDD가 동결 상한 `35%` 이상 | 신규 진입 차단, 기존 포지션 종료 허용 |
| `ACCOUNT_RECONCILIATION_*` | wallet 기준점·최신성·transaction sync 불확실 | 신규 진입 보류, 원장 재동기화 |
| `ACCOUNT_LEDGER_MISMATCH_*` | wallet 변화와 transaction 변화 불일치 | 신규 진입 차단, 반복 대사 후 사람 확인 |

## 7. 동시성 및 성능

- 한 프로세스에서는 Kotlin `Mutex`로 H4 평가, private stream callback, 수동 reconcile을 직렬화한다.
- 다중 replica를 지원하지 않는다. Compose replica가 1이 아닌 경우 배포 전 검증을 실패시킨다.
- 체결·종료손익 REST 복구는 명시적 시작·종료 시각을 전달하고 Bybit 최대 범위에 맞춰 7일 이하의
  연속 구간으로 분할한다. 각 구간은 100건 단위 cursor를 끝까지 읽고 전체 요청 합계가 1,000페이지를
  넘거나 같은 cursor가 반복되거나 result가 누락되면 부분 원장을 반환하지 않고 fail closed한다.
- pending 주문 복구는 영속 상태 시각보다 5분 앞에서 현재까지 조회한다. 종료손익 회계 동기화는
  마지막 성공 시각보다 5분 앞에서 재조회하고, 실패 시 성공 워터마크를 전진시키지 않는다. 재시작
  직후에는 영속 전략 상태 시각을 복구 시작점으로 사용한다.
- 겹쳐 읽은 체결은 `execId`, 종료손익은 거래소 order ID를 우선 identity로 사용해 멱등 제거한다.
- websocket은 빠른 wake-up 용도이며 REST 대사가 복구 source다.
- API rate limit은 전환 실행을 늦추더라도 재시도 폭주보다 fail closed를 우선한다.
- 실행 중 승인 검증이 무효가 되거나 승인 보고서 계산이 실패해도 pending 주문 복구와 소유가 확인된
  포지션 관리는 중단하지 않는다. 신규 진입은 즉시 차단하고, 현재 수량 전체에 bounded reduce-only IOC
  종료를 제출한다. 명확히 미체결된 종료만 1분 이후 새 client order ID로 재시도하며 불명확한 응답은
  자동 재주문하지 않는다. 거래소 수량과 영속 상태 수량이 다르면 포지션을 임의 종료하지 않는다.
- 안전 종료는 포지션을 감소시키는 전체 수량 주문이므로 일반 신규 주문의 minimum-notional 사전 차단을
  적용하지 않는다. 거래소의 실제 수락·미체결 결과는 동일한 pending 복구 상태 머신으로 확인한다.

## 8. 변경 파일 계획

| 영역 | 변경 |
|---|---|
| `bot-engine/strategy` | 순수 target planner와 상태 모델 |
| `bot-engine/execution` | live application service와 account-mode/instrument port 모델 |
| `bot-ledger` | Live checkpoint/event schema, migration, adapter |
| `bot-exchange-bybit` | account mode, margin mode, instrument metadata 매핑 |
| `bot-app` | 별도 env, loop, 승인 receipt loader, API/알림 |
| `config` | 기본 `NOT_APPROVED` receipt와 schema |
| `.github/workflows` | 기존 M5 loop 영구 차단, trend live receipt gate |

## 9. 검증 계획

| 시나리오 | 유형 | 예상 결과 |
|---|---|---|
| 100/660/1000 USDT 수량 | unit | 기존 Kotlin 실행 모델과 동일 수량 |
| 최소 수량이 0.85 상한 초과 | unit | `NO_TRADE` |
| 같은 H4 decision 재호출 | unit/integration | 주문 1개 이하 |
| 반대 신호와 partial exit | integration | 종료 완료 전 entry 0개 |
| ack 직후 process kill | fault injection | 재시작 후 client ID 조회, 중복 0개 |
| fill 직후 DB 실패 | fault injection | REST/stream 대사로 한 번만 반영 |
| unitized NAV 기준점 미완성 | integration | 신규 entry 0개, 위험 사유 저장 |
| wallet/transaction 변화 일치 | integration | 대사 `MATCHED`, 해당 사유 해제 |
| account MDD 35% 이상 | unit/integration | 신규 entry 0개, reduce-only exit 허용 |
| receipt hash 변경 | configuration | 앱 시작 실패 |
| Shadow gate 미달 | configuration | private gateway를 생성하지 않음 |
| hedge/isolated/15배 설정 | adapter | fail closed |
| 전체 역사 replay | parity | Shadow 전환·수량과 target planner 명령 일치 |
| TESTNET 최소 주문 | manual approval | 주문·종료·복구 확인 후에도 LIVE 미승인 유지 |

## 10. 리스크와 미결정 사항

- 공개 API의 `lastPrice`와 실제 market fill 차이는 제거할 수 없으므로 forward Shadow와 최소 주문에서
  추적 오차 분포를 측정해야 한다.
- Bybit 계정의 margin/position mode 조회·변경 API 계약을 구현 전에 공식 문서와 실제 계정에서 확인한다.
- 자동 account-level flatten은 전략 수익 분포를 바꾸므로 v1에 추가하지 않는다. 운영자가 허용한
  MDD와 장애 시 수동 `FLATTEN` 정책을 live 승인 때 다시 확인해야 한다.
- 현재 문서는 실행 구조를 준비하기 위한 설계다. 90일 Shadow 결과가 실패하면 live service를
  활성화하지 않고 후보를 폐기한다.

## 11. 구현 현황

2026-08-08 기준으로 다음 경로가 연결됐다.

- 승인 영수증, 불변 Shadow 증거, 승인 보고서의 SHA-256과 protocol/policy/session을 앱 부팅 시 검증한다.
- 승인 보고서는 고정된 15개 gate ID를 중복·누락 없이 모두 `PASS`해야 하며, 보고서 자체의 자동·실거래 권한은 계속 `false`여야 한다.
- 현재 SQLite Shadow checkpoint가 승인 증거보다 과거로 롤백되지 않았고 같은 연속 세션이며, 정확히 하나의 상태 시각과 일치하는 `SESSION_STARTED`, 최신성·MDD·노출·청산 및 모든 현재 forward gate를 계속 통과하는지 확인한다.
- 동결 이벤트는 event ID, session ID, protocol fingerprint, symbol, type, event/observation time을 보존하며 런타임이 중복·다른 세션·다른 전략·비인과 시간 순서를 독립적으로 거부한다.
- 이 이벤트 정체성 계약은 Shadow evidence schema `2`이며, 이전 schema `1` pending 산출물은 승인에 재사용하지 않고 현재 세션에서 다시 export한다.
- 위 검증은 `BybitPrivateClient` 생성보다 먼저 수행되므로 실패 시 개인 API 조회나 주문 경로가 구성되지 않는다.
- 승인된 경우에만 `VolumeConfirmedTrendLiveLoop`를 시작하고, PAUSE 상태에서도 거래소 포지션 대사는 유지한다.
- 루프 시작 뒤 승인이 상실되면 신규 노출은 만들지 않지만, 이미 기록된 pending 주문을 먼저 복구하고
  영속 상태와 방향·수량이 일치하는 기존 포지션은 `TREND_APPROVAL_REVOKED_EXIT` reduce-only 주문으로
  정리한다. 승인 이력이 전혀 없는 `DISABLED` 상태에서는 개인 API를 조회하지 않는다.
- 동일한 승인 차단 또는 안전 중단은 상태가 바뀌지 않는 한 원장과 Discord에 반복 기록하지 않는다.
- `GET /strategy/volume-confirmed-trend/live`에서 checkpoint와 append-only 이벤트를 인증된 운영자에게 제공한다.
- 승인된 실행 중 USDT account equity를 1분 간격으로 공통 account snapshot에 저장하고, 복구에서 확인한
  모든 H4 `execId`의 가격·수량·수수료·실현 PnL을 공통 체결 원장에 중복 없이 저장한다.
- H4 실행 경로가 기존 공격형 reconciliation loop와 격리된 상태에서도 종료손익은 1분, USDT transaction
  log는 5분 간격으로 별도 동기화한다. 종료손익은 영속 상태 기반 시작점부터 현재까지 7일 이하의
  연속 API 구간으로 복구하고, 성공 이후에도 5분을 겹쳐 재조회한다. `vct-*` client order ID 또는 그
  ID를 가진 exact execution으로 소유권이 확인된 BTCUSDT 종료건만 H4 성과로 귀속하며, 수동 주문은 제외한다.
- transaction log의 거래 수수료, funding, cash flow, 입출금 변화는 거래소 transaction ID로 멱등 저장하고,
  wallet balance 변화와 원장 변화의 대사 상태를 `BASELINE`, `MATCHED`, `MISMATCH`, `SYNC_ERROR`로 보존한다.
- 종료 사유가 일반 `CLOSED_PNL`이어도 H4 client order ID가 확인되면 `STRATEGY_EXIT`으로 분류하고, 실제
  종료 손익과 account equity로 공통 성과 스냅샷을 갱신한다.
- 계좌 입출금을 전략 손익과 분리한 unitized NAV를 Live checkpoint에 저장한다. 시작 기준점, 유효하지 않은
  NAV, 10분보다 오래된 위험 상태는 fail closed하고 동결 protocol의 MDD 상한 `35%` 이상에서는 신규
  진입만 차단한다.
- USDT wallet 대사가 `MATCHED`가 아니거나 10분보다 오래됐으면 신규 진입을 보류한다. 위험 차단 중에도
  기존 H4 포지션의 reduce-only 종료와 계좌·거래내역 대사는 계속 실행한다.
- 위험 상태 JSON은 schema v2로 저장하며 schema v1 checkpoint는 위험 기준점이 없는 상태로 읽어 다음
  대사에서 안전하게 기준점을 다시 만든다.
- `HALTED` 상태에서도 신규 주문은 만들지 않지만 실제 포지션과 account snapshot 관측은 계속한다.
- 의도 저장 실패, 주문 응답 불명확, 주문 ack 저장 실패, 체결 projection 저장 실패, 부분 체결 및
  exact-order 증거 누락을 장애 주입 테스트로 검증한다.
- 승인이나 주문 경로를 켜지 않은 TESTNET 프로세스에서도 인증된
  `GET /strategy/volume-confirmed-trend/exchange-contract`로 account/position/instrument 읽기만 수행해
  Unified/Cross/one-way/1배/수량 단위 계약과 불일치 코드를 확인할 수 있다. 이 점검은 레버리지 변경,
  주문 생성·취소, 잔고·체결 조회를 수행하지 않는다.
- Shadow 평가 성공 뒤 현재 승인 보고서를 다시 계산하고 세션·전체 상태·게이트 상태 조합이 바뀐 경우에만
  Discord/Telegram 전진 검증 알림을 보낸다. 검토 준비 알림도 자동·실거래 주문 차단과 별도 사람 승인을
  명시하며 같은 상태의 H4 반복 평가는 중복 알림을 만들지 않는다.
- 온프레미스 배포는 단순 health 확인 뒤에도 선택한 실행 프로필을 다시 검증한다. H4 Shadow는 동결
  protocol identity와 자동·실거래 주문 권한 `false`를, read-only TESTNET과 승인된 H4 실행은 읽기 전용
  exchange contract의 `available=true`, `valid=true`를 충족하지 못하면 배포를 실패시킨다.
- 실행 중인 SQLite는 배포 전에 online backup으로 복제하고 `PRAGMA quick_check`와 SHA-256을 남긴다.
  구버전 이미지에는 짧은 pause 동안 DB/WAL을 함께 복사하는 호환 경로를 사용하며, 재기동 뒤 기존 H4
  Shadow `sessionId`가 바뀌면 연속 관측으로 인정하지 않고 배포를 실패시킨다.
- 백업은 실제 배포 전에 임시 Docker volume으로 복원해 현재 이미지의 DB open/migration과 `/health`를
  검증한다. 복구 드릴은 `--network none`, 주문 경로 전부 비활성, 임시 control token으로 실행하며 현재
  H4 세션의 checkpoint·`SESSION_STARTED`·미무효화 계약이 깨지면 production volume을 건드리지 않고 실패한다.
- CI도 실제 빌드 이미지로 빈 운영 DB 생성, SQLite snapshot, 임시 volume 복원, 앱 health까지 같은 경로를
  실행해 Docker/SQLite/schema/startup 계약 변화가 온프레미스 배포 전에 드러나게 한다.

아직 완료되지 않은 항목은 실제 Bybit TESTNET 최소 주문,
fresh Bybit Shadow 90일 및 별도 사람 승인이다. 일 손실·연속 손실 제한은 미완료 항목이 아니라 v1의
동결 역사 계약 밖이므로 의도적으로 포함하지 않는다.
따라서 기본 승인 파일과
`BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED=false`는 유지한다.

## 11. 완료 체크리스트

- [x] 설계 배경과 현재 코드 경계를 연결했다.
- [x] 기존 실행기 재사용과 별도 상태 머신을 비교했다.
- [x] 도메인 불변식, 원장, 트랜잭션, 실패 복구, 동시성을 정의했다.
- [x] 승인 전 주문 불가능 조건을 고정했다.
- [x] 순수 target planner와 결정적 테스트를 구현한다.
- [x] Live store와 fault-injection 테스트를 구현한다.
- [x] Bybit account mode/instrument 및 exact-order adapter를 구현한다.
- [ ] 90일 Shadow와 사람 승인 후에만 TESTNET/LIVE 경로를 활성화한다.

## 12. 공식 계약 근거

- Bybit V5 주문 생성: `orderLinkId`는 36자 이하의 고유 값이어야 한다.
  <https://bybit-exchange.github.io/docs/v5/order/create-order>
- Bybit V5 실시간 주문 조회: `orderLinkId`로 최근 체결·취소 상태를 조회할 수 있으나 서버 재시작 뒤
  종료 주문이 사라질 수 있다. <https://bybit-exchange.github.io/docs/v5/order/open-order>
- Bybit V5 주문 이력: 최근 24시간의 취소·거절 주문과 체결 주문을 조회할 수 있다.
  <https://bybit-exchange.github.io/docs/v5/order/order-list>
- Bybit V5 체결 이력: 한 주문에 여러 체결이 존재할 수 있고 `orderLinkId`로 조회할 수 있다.
  <https://bybit-exchange.github.io/docs/v5/order/execution>
