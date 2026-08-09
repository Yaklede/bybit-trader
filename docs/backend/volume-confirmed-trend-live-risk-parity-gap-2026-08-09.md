# H4 Live 위험 정책 패리티 해결 보고서

## 판정

Human owner는 2026-08-09 선택지 A를 승인했다.

- H4 v1의 일 손실 신규 진입 차단: `DISABLED`
- H4 v1의 연속 손실 신규 진입 차단: `DISABLED`
- 계좌 MDD 신규 진입 차단: `35%` 유지
- 위험 상태 및 지갑 대사 freshness: 유지
- 지갑 불일치, 원장 동기화, 재고 및 주문 소유권 fail-closed 검사: 유지
- 일반 M5 실행기의 일 손실·연속 손실 설정: 변경 없음

수정된 H4 정책은 동결 외부 경로와 Node/Kotlin에서 다시 일치했다. 필수
`LIVE_RISK_POLICY_PARITY` 게이트는 `PASS`다. 그러나 artifact와 승인 보고서는 계속
`automaticExecutionAllowed=false`, `liveExecutionAllowed=false`를 선언한다. fresh 90일 Shadow와 별도 사람
승인을 완료하기 전에는 Live 실행을 허용하지 않는다.

## 해결 전 결함

공통 실행 설정의 `일 손실 3%`와 `3연속 손실` 한도가 백테스트에 포함되지 않은 채 H4 Live에 연결됐다.
해결 전 정책의 660 USDT 기본 비용 재생은 다음과 같았다.

| 항목 | 값 |
|---|---:|
| 종료 거래 | 3건 |
| 차단 진입 | 162건 |
| 종료 잔고 | 574.39010661 USDT |
| 누적 수익률 | -12.97119597% |

3연속 손실 이후에는 승리 거래가 실행되지 않아 카운터를 초기화할 수 없었다. 따라서 이 정책은 안전장치가
아니라 동결 전략을 영구 정지시키는 별도 전략 상태 전이였다.

## 구현 계약

H4 전용 정책은 마법값 대신 nullable 임계값으로 비활성 상태를 표현한다.

```text
maximumDailyLossFraction = null
maximumAccountDrawdownFraction = 0.35
maximumConsecutiveLosses = null
```

`ExecutionRiskThresholdEvaluator`는 값이 있는 임계값만 평가한다. MDD 판정은 항상 평가하며, 위험 상태가
없거나 오래됐거나 NAV가 준비되지 않은 경우에는 기존과 동일하게 진입을 차단한다. H4 정책 factory는
일반 실행 설정의 일 손실·연속 손실 값을 H4에 복사하지 않는다.

## 재현 명령

Node 재생:

```bash
node scripts/volume-confirmed-trend-node-parity.mjs \
  --maximum-daily-loss-fraction=disabled \
  --maximum-account-drawdown-fraction=0.35 \
  --maximum-consecutive-losses=disabled \
  --out=build/research/volume-confirmed-trend-node-risk-parity-a.json
```

Kotlin 재생:

```bash
GRADLE_USER_HOME=.gradle-local ./gradlew :modules:bot-app:runVolumeConfirmedTrendParity \
  --args="--protocol config/volume-confirmed-trend-ensemble-v1.json \
  --db build/research/binance-volume-confirmed-trend-external-v1.sqlite \
  --out build/research/volume-confirmed-trend-kotlin-risk-parity-a.json \
  --maximum-daily-loss-fraction disabled \
  --maximum-account-drawdown-fraction 0.35 \
  --maximum-consecutive-losses disabled"
```

런타임 대조:

```bash
node scripts/verify-volume-confirmed-trend-parity.mjs \
  --node=build/research/volume-confirmed-trend-node-risk-parity-a.json \
  --kotlin=build/research/volume-confirmed-trend-kotlin-risk-parity-a.json
```

동결 artifact 생성:

```bash
node scripts/volume-confirmed-trend-live-risk-parity-audit.mjs \
  --maximum-daily-loss-fraction=disabled \
  --maximum-account-drawdown-fraction=0.35 \
  --maximum-consecutive-losses=disabled \
  --risk-state-maximum-age-seconds=600 \
  --wallet-reconciliation-maximum-age-seconds=600 \
  --wallet-reconciliation-confirmed-mismatch-count=2 \
  --node-risk-parity=build/research/volume-confirmed-trend-node-risk-parity-a.json \
  --kotlin-risk-parity=build/research/volume-confirmed-trend-kotlin-risk-parity-a.json \
  --out=config/volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json
```

## 검증 결과

Source:

- `config/volume-confirmed-trend-ensemble-v1.json`
- `config/volume-confirmed-trend-ensemble-v1-external-result.json`
- `build/research/binance-volume-confirmed-trend-external-v1.sqlite`
- `config/volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json`

패리티 결과:

| 항목 | 값 |
|---|---:|
| Node/Kotlin 상태 | `PARITY_PASS` |
| 진입 명령 | 165개 |
| 자본·비용 조합 | 9개 |
| 전체 종료 거래 | 1,485건 |
| 전체 차단 진입 | 0건 |
| 숫자 허용 오차 | `1e-8` |

660 USDT 기본 비용 결과:

| 항목 | 동결 외부 결과 | A 정책 재생 |
|---|---:|---:|
| 종료 잔고 | 3,605.34525093 | 3,605.34525093 |
| 누적 수익률 | 446.26443196% | 446.26443196% |
| 일복리 | 0.07340346% | 0.07340346% |
| 종료 거래 | 165건 | 165건 |
| 차단 진입 | 0건 | 0건 |
| 보수적 intrabar MDD | 30.58189901% | 30.58189901% |

100/660/1,000 USDT와 비용 1/1.5/2배의 모든 조합은 165건을 종료했고 누적 수익률이 양수였다. 가장 높은
관측 MDD도 35%보다 낮았다. A 정책은 동결 수익 경로를 변경하지 않는다.

## 기계적 차단

승인 로더는 다음을 모두 검증한다.

- artifact SHA-256
- protocol, 외부 결과, 원본 DB SHA-256
- H4 일 손실·연속 손실 값이 실제 JSON `null`인지 여부
- MDD가 35% 이하인지 여부
- Node/Kotlin 결과 hash와 165개 명령
- 9개 조합에서 165개 종료, 0개 차단인지 여부
- artifact의 자동·Live 실행 권한이 모두 `false`인지 여부

런타임 정책이 동결 정책과 다르면 `LIVE_RISK_POLICY_PARITY`는 다시 실패한다. 승인 영수증만 수정해 이
검사를 우회할 수 없다.

## 남은 승인 조건

- [x] Human owner가 A를 선택했다.
- [x] H4 v1에서 일 손실·연속 손실 차단을 명시적으로 비활성화했다.
- [x] MDD 35%와 운영 fail-closed 검사를 유지했다.
- [x] Node/Kotlin 9개 조합 패리티를 통과했다.
- [x] 동결 외부 결과와 165개 거래 경로를 재현했다.
- [x] 새 결정적 artifact와 승인 게이트를 갱신했다.
- [ ] fresh 연속 Shadow 90일과 모든 forward gate를 통과한다.
- [ ] 별도 사람 승인 영수증을 발급한다.
- [ ] 승인된 TESTNET 최소 주문 및 거래소 계약 점검을 완료한다.

역사 패리티 통과는 미래 수익 보장이나 Live 승인과 동의어가 아니다. 실제 wallet snapshot은 H4 내부
equity 상태와 거래소 체결을 추가로 관측하므로, Shadow 단계에서 35% MDD와 실행 오차를 다시 검증한다.
