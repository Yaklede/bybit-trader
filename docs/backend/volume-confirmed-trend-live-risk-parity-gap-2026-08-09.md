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

### 위험 정책 재생

Source:

- `build/research/binance-volume-confirmed-trend-external-v1.sqlite`
- `config/volume-confirmed-trend-ensemble-v1.json`
- `scripts/lib/volume-confirmed-trend-research.mjs`

다음 명령은 동결 protocol, 외부 DB, 승인 결과의 SHA-256과 핵심 지표를 먼저 대조한 뒤 현재 Live
한도를 동결 종료 거래 경로에 대입한다. 먼저 Node와 Kotlin이 실제 위험 제한을 포함한 동일 재생을
각각 생성하고 전체 JSON을 대조한다.

```bash
node scripts/volume-confirmed-trend-node-parity.mjs \
  --maximum-daily-loss-fraction=0.03 \
  --maximum-account-drawdown-fraction=0.35 \
  --maximum-consecutive-losses=3 \
  --out=build/research/volume-confirmed-trend-node-risk-parity.json

GRADLE_USER_HOME=.gradle-local ./gradlew :modules:bot-app:runVolumeConfirmedTrendParity \
  --args="--protocol config/volume-confirmed-trend-ensemble-v1.json \
  --db build/research/binance-volume-confirmed-trend-external-v1.sqlite \
  --out build/research/volume-confirmed-trend-kotlin-risk-parity.json \
  --maximum-daily-loss-fraction 0.03 \
  --maximum-account-drawdown-fraction 0.35 \
  --maximum-consecutive-losses 3"

node scripts/verify-volume-confirmed-trend-parity.mjs \
  --node=build/research/volume-confirmed-trend-node-risk-parity.json \
  --kotlin=build/research/volume-confirmed-trend-kotlin-risk-parity.json
```

Parity 결과:

| 항목 | 값 |
|---|---:|
| 상태 | `PARITY_PASS` |
| 진입 명령 | 165개 |
| 자본·비용 조합 | 9개 |
| 위험 정책 적용 후 전체 종료 거래 | 27건 |
| 숫자 허용 오차 | `1e-8` |

그 다음 기존 경로 위반 감사와 위험 정책 적용 후 경로를 하나의 결정적 artifact로 생성한다.

```bash
node scripts/volume-confirmed-trend-live-risk-parity-audit.mjs \
  --maximum-daily-loss-fraction=0.03 \
  --maximum-account-drawdown-fraction=0.35 \
  --maximum-consecutive-losses=3 \
  --risk-state-maximum-age-seconds=600 \
  --wallet-reconciliation-maximum-age-seconds=600 \
  --wallet-reconciliation-confirmed-mismatch-count=2
```

660 USDT, 기본 비용 1배 재생 결과:

| 항목 | 값 |
|---|---:|
| 최초 일 손실 한도 초과 | 1번째 종료 거래 |
| 최초 한도 초과 시각 | 2020-04-06T16:00:00Z |
| 해당 UTC 일 시작 H4 equity 프록시 | 633.80362426 USDT |
| 종료 직후 누적 실현 equity | 608.61493309 USDT |
| 당일 손실 프록시 | 3.97421066% |
| 최대 연속 손실 | 11건 |
| 최초 3연속 손실 | 3번째 종료 거래 |
| 최초 3연속 손실 종료 시각 | 2020-04-12T16:00:00Z |
| 3개 종료 거래 누적 후 잔고 | 573.37095348 USDT |
| 3개 종료 거래 누적 수익률 | -13.12561311% |
| 이후 동결 경로 거래 | 162건 |

첫 종료 직후부터 3% 일 손실 한도가 반대 포지션 진입 시각을 바꿀 수 있다. 따라서 이후 체결과 손익은
동결 경로와 달라지며, `573.37 USDT`는 실제 Live 예상 잔고가 아니라 동결된 첫 세 종료 거래를 그대로
적용한 반사실적 접두 결과다. 만약 동일한 세 종료 거래가 발생하면 신규 진입이 차단되고, 승리 거래가
실행될 수 없어 `consecutiveLosses`도 자동 초기화되지 않는다. 어느 경우든 동결 결과의 165건 거래와
446.26% 누적 수익 경로를 현재 계약으로 재현할 수 없다.

생성되는 `build/research/volume-confirmed-trend-live-risk-parity-audit.json`은 이 한계를
`livePathSimulation=false`로 명시하며 `status=FAIL`, `riskPolicyParityPassed=false`로 기록한다.

### 위험 정책 적용 경로

정적 접두 감사와 별도로 `simulateTrendRun`에 현재 Live 임계값을 적용해, 포지션 축소는 항상 허용하고
신규 노출만 차단하는 H4 결정 경계 재생을 수행했다. Kotlin 시뮬레이터와 Live 회로차단기는
`ExecutionRiskThresholdEvaluator`의 동일 임계값 코드를 사용하고, Node 구현은 전체 외부 구간에서 Kotlin
결과와 필드 단위로 일치해야 artifact가 생성된다.

660 USDT, 기본 비용 1배 결과:

| 항목 | 기존 동결 경로 | 현재 Live 위험 정책 적용 |
|---|---:|---:|
| 종료 잔고 | 3,605.34525093 | 574.39010661 |
| 누적 수익률 | 446.26443196% | -12.97119597% |
| 일복리 | 0.07340346% | -0.00600375% |
| 종료 거래 | 165건 | 3건 |
| 차단된 진입 | 0건 | 162건 |
| 최대 보수적 intrabar MDD | 30.58189901% | 15.88814030% |

차단 162건 중 첫 1건은 일 손실 제한, 이후 161건은 연속 손실 제한이다. 3번째 손실 이후
`consecutiveLosses=3`이 유지되고 승리 거래가 실행될 수 없어 외부 구간 종료까지 복구되지 않는다.
100/660/1,000 USDT와 비용 1/1.5/2배의 9개 조합도 모두 3건 거래 후 정지했고 누적 수익률은
`-12.53688302%`부터 `-13.22014364%`였다.

이 재생은 H4 진입 경계의 정책 결과를 정확히 비교하지만 실제 거래소 체결 예측은 아니다. Live wallet
snapshot은 H4 내부의 추가 equity 상태를 관측할 수 있으므로 artifact는 계속
`policyReplay.livePathSimulation=false`를 선언한다. 다만 현재 정책이 검증된 165개 거래 경로를 재현하지
못하고 영구 정지 상태가 된다는 결론에는 영향을 주지 않는다.

## 기계적 차단

재생 결과는
`config/volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json`에 결정적으로 동결했다.
승인 로더는 이 파일의 SHA-256, protocol SHA, 외부 결과 SHA, 원본 DB SHA, baseline 핵심 지표, Node/Kotlin
재생 hash와 9개 stress matrix의 거래·차단 합계를 모두 검증한다. 현재 artifact의
`riskPolicyParityPassed=false`는 승인 보고서의 필수
`LIVE_RISK_POLICY_PARITY` 게이트를 `FAIL`로 만들고 상위 상태를 `RUNTIME_PARITY_REQUIRED`로 유지한다.
동결 위험 계약에는 일 손실, 계좌 MDD, 연속 손실, 위험 상태 freshness, 지갑 대사 freshness와 불일치
확정 횟수를 함께 기록한다. 앱은 환경 설정에서 계산한 실제 H4 위험 정책과 이 계약을 필드별로 대조하며,
하나라도 다르면 artifact가 향후 `PASS`가 되더라도 필수 게이트를 다시 실패시킨다.

따라서 90일 Shadow 정량 조건을 모두 충족하거나 승인 영수증만 `APPROVED`로 바꿔도 Live 실행은 열리지
않는다. 현재 보고서와 필수 게이트를 다시 검증하는 시작 경로가 실패하며, 같은 artifact는 Docker 이미지와
온프레미스 배포 패키지에도 포함된다. A/B 결정 후 새 위험 정책 검증이 실제로 통과하고 동결 artifact와
코드 fingerprint를 함께 갱신해야만 이 게이트를 `PASS`로 바꿀 수 있다.

## 코드 위치

- Live 기본 위험 한도: `VolumeConfirmedTrendLiveState.kt`의 `VolumeConfirmedTrendLiveRiskPolicy`
- 앱 설정과 H4 한도 결합: `VolumeConfirmedTrendLiveRiskPolicyFactory.kt`
- 신규 진입 차단 판정: `ExecutionRiskCircuitBreaker.evaluate`
- 동결 역사 재생: `volume-confirmed-trend-research.mjs`
- 위험 패리티 진단: `volume-confirmed-trend-live-risk-parity-audit.mjs`
- Node/Kotlin 위험 재생 대조: `verify-volume-confirmed-trend-parity.mjs`
- 공통 Kotlin 임계값 판정: `ExecutionRiskThresholdEvaluator`
- 동결 위험 패리티 결과: `volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json`
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
- [x] 미해결 상태를 필수 승인 게이트로 연결해 영수증만으로 우회할 수 없게 한다.
- [x] 현재 Live 위험 정책을 9개 자본·비용 조합에서 Node/Kotlin으로 재생하고 parity를 검증한다.
- [ ] 선택한 위험 정책이 protocol/approval fingerprint에 포함된다.
- [ ] 백테스트와 Live 위험 상태 전이가 동일하다는 회귀 테스트가 통과한다.
- [ ] 외부/비용 스트레스와 runtime parity가 다시 통과한다.
- [ ] 그 이후에만 fresh Shadow 90일과 사람 승인을 진행한다.
