# H4 Live 위험 정책 패리티 결함 보고서

## 판정

현재 `volume-confirmed-trend-ensemble-v1`의 역사/외부 백테스트와 H4 Live 실행은 동일한 전략이 아니다.
Live에는 백테스트에 없는 `일 손실 3%`와 `3연속 손실` 신규 진입 차단이 적용된다. 특히 연속 손실
카운터는 이후 승리 거래로만 초기화되므로, 세 번 차단된 뒤에는 자동으로 회복할 수 없다.

이 상태에서는 90일 Shadow와 사람 승인이 통과하더라도 `SIGNAL_ENABLED`를 허용하면 안 된다. 기본
`NOT_APPROVED` 영수증과 `BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED=false`를 유지한다.

## 근거

### 동결 외부 결과

Source: `config/volume-confirmed-trend-ensemble-v1-external-result.json`

| 항목 | 값 |
|---|---:|
| 외부 구간 | 2020-01-01 ~ 2026-08-01 UTC, end-exclusive |
| 시작 잔고 | 660 USDT |
| 종료 잔고 | 3,605.34525093 USDT |
| 누적 수익률 | 446.26443196% |
| CAGR | 30.73573214% |
| 종료 거래 | 165건 |
| 승률 | 26.06060606% |
| 최대 보수적 intrabar MDD | 30.58189901% |

### 연속 손실 재생

Source:

- `build/research/binance-volume-confirmed-trend-external-v1.sqlite`
- `config/volume-confirmed-trend-ensemble-v1.json`
- `scripts/lib/volume-confirmed-trend-research.mjs`

동결 protocol과 같은 `buildTrendCommands`/`simulateTrendRun`을 660 USDT, 기본 비용 1배로 재생한 결과:

| 항목 | 값 |
|---|---:|
| 최대 연속 손실 | 11건 |
| 최초 3연속 손실 | 3번째 종료 거래 |
| 최초 3연속 손실 종료 시각 | 2020-04-12T16:00:00Z |

따라서 현재 Live 정책을 역사 구간 처음부터 적용하면 세 번째 거래 뒤 신규 진입이 차단된다. 이후 승리
거래가 실행될 수 없으므로 `consecutiveLosses`도 자동 초기화되지 않는다. 동결 결과의 165건 거래와
446.26% 누적 수익 경로를 재현할 수 없다.

## 코드 위치

- Live 기본 위험 한도: `VolumeConfirmedTrendLiveState.kt`의 `VolumeConfirmedTrendLiveRiskPolicy`
- 앱 설정과 H4 한도 결합: `VolumeConfirmedTrendLiveRiskPolicyFactory.kt`
- 신규 진입 차단 판정: `ExecutionRiskCircuitBreaker.evaluate`
- 동결 역사 재생: `volume-confirmed-trend-research.mjs`
- 기존 설계 선언: `volume-confirmed-trend-live-execution.md`

## 원인

공통 실행 위험 회로차단기를 H4 Live 경로에 연결하면서 일반 실행 기본값인 `3%`와 `3회`를 동결 H4
전략에도 그대로 적용했다. 그러나 해당 두 상태 전이는 protocol, 외부 백테스트, 비용 스트레스,
Kotlin/Node 패리티에 포함되지 않았다. MDD 35%와 지갑/원장 freshness는 승인·운영 안전 계약에 포함돼
있지만 일 손실과 연속 손실은 포함돼 있지 않다.

## 선택지

### A. 동결 v1과 일치시키기

- 일 손실과 연속 손실 차단을 H4 v1에서 명시적으로 `DISABLED`로 모델링한다.
- MDD 35%, 원장 대사, 위험 상태 freshness, 미확인 주문 차단은 유지한다.
- `null` 또는 명시적 enum을 사용하고 `100%`, `100회` 같은 마법값은 사용하지 않는다.
- 위험 정책 fingerprint를 승인 artifact와 배포 이미지에 포함한다.
- 기존 역사/외부 결과가 실행 계약과 동일함을 다시 패리티 검증한다.

장점: 이미 검증한 전략 수익 경로와 맞는다. 단점: 운영 안전 한도를 완화하므로 사람의 명시적 승인이
필요하다.

### B. 손실 중단을 전략에 포함하기

- 일 손실, 연속 손실, 재개 조건 또는 cooldown을 protocol 파라미터로 고정한다.
- 개발, 외부 거래소, 비용 2배, 무작위 구간, Kotlin/Node/runtime replay를 처음부터 다시 수행한다.
- 세 번 차단 후 영구 정지가 아니라 사전에 고정한 재개 조건을 구현한다.
- 새 protocol ID와 SHA를 사용하고 기존 외부 결과와 90일 Shadow를 재사용하지 않는다.

장점: 손실 제어까지 수익 분포에 포함한다. 단점: 새 전략 연구이며 현재 승인 증거가 전부 무효화된다.

## 권고

현재 v1의 목적이 검증된 H4 수익 경로를 실제 실행하는 것이라면 A가 최소 변경이다. 다만 이는 위험 한도
완화이므로 자동 적용하지 않는다. Human owner가 A 또는 B를 선택하기 전까지 H4 Live를 계속 차단한다.

## 완료 조건

- [ ] Human owner가 A 또는 B를 명시적으로 선택한다.
- [ ] 선택한 위험 정책이 protocol/approval fingerprint에 포함된다.
- [ ] 백테스트와 Live 위험 상태 전이가 동일하다는 회귀 테스트가 통과한다.
- [ ] 외부/비용 스트레스와 runtime parity가 다시 통과한다.
- [ ] 그 이후에만 fresh Shadow 90일과 사람 승인을 진행한다.
