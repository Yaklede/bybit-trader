# Bybit 이벤트 흐름 증거 데이터 계약

## 문제

6년치 M1/M5/M15 캔들은 가격과 총거래량만 보존한다. 이 데이터로는 매수·매도 주도 체결, top-of-book 유동성 추가·제거, microprice 압력, 흡수와 고갈을 구분할 수 없다. 기존 OHLCV 후보가 외부 시기에서 음수 기대값으로 바뀐 뒤 같은 캔들 임계값을 더 조정하는 것은 허용하지 않는다.

공식 Bybit L2와 public trade 아카이브를 사용하더라도 원본 식별자, 일별 완결성, live 수집과 같은 특징 계산 계약이 없으면 결과를 재현하거나 실시간 신호와 비교할 수 없다.

## 목표

- 공식 L2 snapshot/delta를 메시지 순서대로 재생한다.
- 현재 live collector와 같은 top-50 event-weighted imbalance/spread를 계산한다.
- top-5 호가 추가·제거, microprice, 분중 midpoint 경로를 연구 특징으로 보존한다.
- public trade gzip의 실제 SHA-256과 바이트 수를 계산한다.
- taker 방향별 VWAP, 최대 단일 체결, 분중 체결 가격 경로를 보존한다.
- 한 UTC 날짜의 L2·체결·M1/M5/M15가 모두 연속일 때만 연구 가능 상태로 판정한다.

## 비목표

- 이 데이터 import 자체는 전략 승인이나 주문 활성화가 아니다.
- 공개 L2만으로 개별 주문 ID, maker queue 위치, RPI/hidden 유동성을 복원하지 않는다.
- 호가 제거량을 취소량이나 체결량으로 단정하지 않는다.
- archive의 exchange-time 재생을 실제 네트워크 지연이 포함된 live 수집과 동일하다고 주장하지 않는다.
- liquidation event가 없는 날짜를 청산 0으로 해석하지 않는다.

## 원천과 인과성

Order book은 공식 일별 `contract/orderbook` ZIP의 snapshot/delta를 원본 순서로 처리한다. live parser와 동일하게 matching-engine `cts`를 특징의 분 경계로 사용하고, 없는 과거 payload만 `ts`로 대체한다. `ts`와 `cts` 중 하나라도 역행하거나 첫 snapshot 이전 delta, 빈 한쪽 호가, crossed book, top-50 미달, 1,440분 미완결이 발생하면 날짜 전체를 거절한다.

각 메시지는 상태 변경 후 한 표본이 된다. 따라서 기존 `orderBookImbalanceBars.sample_count`는 분당 메시지 수이고, 평균 imbalance와 spread는 live collector와 같은 event-weighted 정의를 사용한다. top-5 추가·제거량은 delta 적용 전후 top-5에 포함된 가격의 실제 수량 변화만 합산한다. snapshot은 새 epoch의 기준 상태이며 추가량으로 세지 않는다.

Public trade는 공식 일별 gzip을 timestamp 순서대로 읽는다. `side=Buy`는 taker buy, `side=Sell`은 taker sell로 보존한다. 양수 M1 캔들 거래량이 있는데 해당 분의 체결이 없으면 날짜를 거절한다. 캔들 거래량이 정확히 0인 분만 명시적 zero-flow bar로 채울 수 있다.

전략 판단은 닫힌 M1 event bar의 `availableAt = openedAt + 1m` 이후에만 가능하다. 해당 분의 종가나 이벤트를 사용한 주문은 가장 빨라도 다음 연속 M1 이벤트에서 체결한다.

## 저장 모델

### `orderBookEventFlowBars`

- 메시지·snapshot 수
- top-5/top-50 평균 imbalance
- top-5 시작·종료·최소·최대 imbalance
- 평균·최대 spread
- 평균 microprice edge
- top-5 bid/ask 추가·제거 notional과 update 수
- 분중 midpoint open/high/low/close

### `takerEventFlowBars`

- 전체 trade 수
- buy/sell VWAP
- buy/sell 최대 단일 체결 notional
- 분중 trade price open/high/low/close

### Provenance manifest

`historicalOrderBookImports`와 `historicalTradeImports`는 source date, URL, 압축 바이트 수, 실제 SHA-256, 이벤트 시각 경계, 이벤트 수, 1분 바 수, importer version을 보존한다. 같은 날짜를 다시 받았을 때 SHA-256이 바뀌면 기존 증거를 덮어쓰지 않고 실패한다.

## 공통 커버리지 게이트

`scripts/bybit-event-flow-coverage-audit.mjs`는 날짜마다 다음을 모두 확인한다.

1. `marketCandles` M1 1,440개, M5 288개, M15 96개
2. `orderBookImbalanceBars` 1,440개
3. `orderBookEventFlowBars` 1,440개
4. `takerFlowBars` 1,440개
5. `takerEventFlowBars` 1,440개
6. L2와 trade manifest의 importer version·minute count·SHA-256

하나라도 빠지면 range fingerprint를 만들지 않는다. 완결 범위의 fingerprint는 날짜와 두 원본 SHA-256을 정렬해 다시 SHA-256으로 묶는다.

## 실제 1일 검증

2026-08-06에 격리 DB로 `2026-06-01`을 재생했다.

| 항목 | 결과 |
|---|---:|
| L2 압축 파일 | 188,313,742 bytes |
| L2 메시지 | 862,955건 |
| L2 SHA-256 | `db5000cac633746d993f964bb2d044d357df5e4e115464869c9a19fa459d65c6` |
| Trade 압축 파일 | 81,925,752 bytes |
| Trade 이벤트 | 2,307,090건 |
| Trade SHA-256 | `26505a7c7db21cbe937de18a0913e048b62f11e44a716826fb5f8725cbd88192` |
| 공통 완결 분 | 1,440분 |
| L2 분당 평균 메시지 | 599.27건 |
| Trade 분당 평균 이벤트 | 1,602.15건 |
| 공통 source fingerprint | `471c22040060d93733e9a6c224f3574bfc69d5a3b9dfdc1553f47756792829ce` |

일반 order-book bar와 event-flow의 top-50 평균 imbalance/spread 최대 차이는 모두 0이었다.

## 실패 처리

- 원본 다운로드·압축 해제·파싱 실패: 해당 날짜 트랜잭션 없음
- 원본 hash 변경: 기존 provenance 유지 후 실패
- L2 상태 이상 또는 분 누락: 해당 날짜 전체 거절
- trade timestamp 역행 또는 양수 거래량 분 누락: 해당 날짜 전체 거절
- manifest나 특징 표 누락: 전략 프로토콜 생성 금지

## 다음 검증

다음 단계는 날짜를 결과를 보기 전에 개발·validation·sealed 블록으로 고정하는 것이다. 개발 후보는 지속형 고갈과 흡수 반전을 별도 가족으로 제한한다. 외부 validation을 통과하지 못한 가족은 sealed 블록을 읽지 않는다. 통과한 후보만 동일 특징을 live Kotlin collector에 이식하고 shadow parity를 검증한다.
