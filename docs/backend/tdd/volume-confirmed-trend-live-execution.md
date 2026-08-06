# 거래량 확인형 추세 전략 실거래 실행 기술 설계

> 작성일: 2026-08-07
> 상태: Draft
> 대상 모듈: `bot-engine`, `bot-exchange-bybit`, `bot-ledger`, `bot-app`

## 1. 설계 배경 및 목적

### 1.1 배경

`volume-confirmed-trend-ensemble-v1`은 인과적 H4 계산 코어, Binance USD-M 외부 이력,
비용 스트레스, Node/Kotlin 계산 패리티, 역사 어댑터/영속 Shadow 패리티를 통과했다.
그러나 현재 운영 경로는 public-data Shadow까지만 구현돼 있으며 개인 주문 경로와 물리적으로
분리돼 있다.

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
  -> private wallet/position/open-order/execution 대사
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
OPEN
EXIT_INTENT_RECORDED
EXIT_SUBMITTED
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

DB intent 기록 후 주문 호출이 실패하면 같은 결정적 client ID로 재조회·재시도한다. 주문 ack 뒤 DB 쓰기가
실패하면 거래소 open order, execution, position 조회로 복구한다. 로컬 상태만 보고 반대 주문을 만들지 않는다.

## 6. 예외 및 실패 처리

| 코드 | 조건 | 처리 |
|---|---|---|
| `TREND_LIVE_NOT_APPROVED` | 영수증 없음/false/hash 불일치 | 부팅 또는 loop 시작 실패 |
| `TREND_SHADOW_GATE_NOT_READY` | 현재 report가 human review 전 | 주문 경로 구성 금지 |
| `TREND_ACCOUNT_MODE_MISMATCH` | hedge/isolated/비 Unified | `HALTED`, Discord 긴급 알림 |
| `TREND_EXPOSURE_LIMIT_EXCEEDED` | 수량 반올림 후 0.85 초과 | 주문 없음 |
| `TREND_POSITION_MISMATCH` | 로컬과 거래소 방향/수량 불일치 | `HALTED`, 신규 진입 차단 |
| `TREND_EXIT_PENDING` | 종료 미확정 | 신규 진입 차단, 대사 반복 |
| `TREND_DATA_STALE` | H4 또는 ticker 지연 | 해당 전환 폐기, 다음 신호까지 대기 |
| `TREND_ORDER_STATE_UNKNOWN` | ack/stream/REST 불일치 | `HALTED`, 자동 재주문 금지 |
| `TREND_LEDGER_WRITE_FAILED` | intent/ack/fill 저장 실패 | loop 중단, 사람 확인 전 재개 금지 |

## 7. 동시성 및 성능

- 한 프로세스에서는 Kotlin `Mutex`로 H4 평가, private stream callback, 수동 reconcile을 직렬화한다.
- 다중 replica를 지원하지 않는다. Compose replica가 1이 아닌 경우 배포 전 검증을 실패시킨다.
- 조회량은 H4 경계당 wallet, position, open order, recent execution으로 제한한다.
- websocket은 빠른 wake-up 용도이며 REST 대사가 복구 source다.
- API rate limit은 전환 실행을 늦추더라도 재시도 폭주보다 fail closed를 우선한다.

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

## 11. 완료 체크리스트

- [x] 설계 배경과 현재 코드 경계를 연결했다.
- [x] 기존 실행기 재사용과 별도 상태 머신을 비교했다.
- [x] 도메인 불변식, 원장, 트랜잭션, 실패 복구, 동시성을 정의했다.
- [x] 승인 전 주문 불가능 조건을 고정했다.
- [x] 순수 target planner와 결정적 테스트를 구현한다.
- [ ] Live store와 fault-injection 테스트를 구현한다.
- [ ] Bybit account mode/instrument adapter를 구현한다.
- [ ] 90일 Shadow와 사람 승인 후에만 TESTNET/LIVE 경로를 활성화한다.
