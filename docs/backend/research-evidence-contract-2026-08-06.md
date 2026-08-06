# 전략 연구 증거 계약 v1

## 판정

전략 연구 결과는 이제 수익률 표나 문서의 `VERIFIED` 문자열만으로 실거래 후보가 될 수 없다. `config/research-approval-policy-v1.json`의 사전 고정 게이트와 `scripts/research-evidence.mjs`가 생성한 fingerprint 증거를 모두 통과해야 한다.

현재 전략 중 이 계약으로 승인된 전략은 없다. 기존 `absa_final_us_v1`과 `multi-horizon-momentum-development-v1`은 `REJECTED`, `multi-horizon-momentum-development-v2`는 `UNVERIFIED` 상태를 유지한다.

## 문제

기존 연구 자산에는 개발 구간, 검증 구간, 비용 스트레스와 실행 패리티가 각각 존재했지만 다음 항목이 하나의 불변 증거로 연결되지 않았다.

- 어떤 Git 코드와 데이터 스냅샷으로 실행했는지
- 전략, 특징 스키마, 시뮬레이터, 실행 계약, 비용 모델, 리스크 정책 중 무엇이 바뀌었는지
- 누적 몇 개 후보를 시도한 뒤 최종 후보를 선택했는지
- 봉인 구간을 이미 다른 후보의 조정에 사용했는지
- 외부 양수 구간, 비용·위험 스트레스, bootstrap, DSR, PBO를 모두 통과했는지
- 연구 통과와 Shadow/Paper 전진 검증, 실거래 승인을 구분했는지

이 공백은 좋은 백테스트 한 번이 승인 근거로 오인될 가능성을 남겼다.

## 목표

1. 후보를 외부·봉인 재생 전에 고정한다.
2. 코드, 데이터, 프로토콜과 정책 파일을 SHA-256으로 묶는다.
3. 실패한 후보를 포함한 누적 시도 횟수를 DSR과 승인 한도에 반영한다.
4. 비용 차감 후 기대값과 구간 일반화를 자동 판정한다.
5. 이미 소비한 봉인 구간을 새 후보 승인에 재사용하지 못하게 한다.
6. 연구 결과만으로 자동 주문이 활성화되지 않게 한다.

## 비목표

- 일복리 `0.2%`를 후보 탐색의 목적함수로 사용하지 않는다.
- 통계 검사를 수익 보장으로 해석하지 않는다.
- 기존 소비 구간을 이름만 바꿔 새로운 봉인 구간으로 취급하지 않는다.
- 승인 리포트가 런타임 프로필을 자동으로 `VERIFIED`로 바꾸지 않는다.

## 고정 입력

실험 정의에는 다음 역할이 모두 있어야 한다.

| 역할 | 의미 |
|---|---|
| `STRATEGY_SOURCE` | 전략 규칙과 파라미터 |
| `SIMULATOR_SOURCE` | 체결·종료 재생 코드 |
| `EXECUTION_CONTRACT` | 신호 시각, 체결가, 동일 봉 충돌 규칙 |
| `FEATURE_SCHEMA` | 전략 입력 특징의 계산 계약 |
| `FEE_MODEL` | 수수료와 슬리피지 가정 |
| `RISK_POLICY` | 거래당 위험, MDD, 청산 제약 |
| `DATA_SNAPSHOT` | 재생 데이터 파일 |

실험을 봉인할 때 위 입력과 개발·외부·봉인 프로토콜, 승인 정책, 봉인 레지스트리의 파일 크기와 SHA-256을 기록한다. 해당 입력 중 하나라도 커밋되지 않은 수정 상태면 `reproducible=false`가 되어 평가에서 `INVALID_EVIDENCE`로 종료된다. 연구와 무관한 파일의 로컬 수정은 fingerprint 결과에 영향을 주지 않는다.

## 통계와 경제성 게이트

승인 정책은 다음을 동시에 요구한다.

- 외부 구간 8개 이상, 양수 구간 비율 75% 이상
- 신선한 봉인 구간 1개 이상
- 기본 위험 `0.5%`와 거래당 위험 `0.25%`, `0.5%`, `0.75%`, `1.0%` 분리 재생
- 비용 배수 `1.0`, `1.5`, `2.0` 분리 재생
- 기본 MDD 30% 이하, 스트레스 MDD 40% 이하
- 청산 0건
- 독립 외부·봉인 기본 조건에서 종료 거래 200건 이상
- 한 거래의 총 양수 PnL 기여율 25% 이하
- 연속 거래 블록을 보존한 5,000회 moving-block bootstrap 기대값 하한 `0R` 초과
- 누적 trial 수와 비정규 수익률을 반영한 DSR 95% 이상
- 후보 전체 수익 행렬을 사용한 CSCV/PBO 20% 이하
- Node/Kotlin 실행 패리티 `PASS`

DSR은 다중 시험과 수익률 비정규성을 함께 보정하고, PBO는 후보 선택 과정에서 개발 구간 승자가 외부 구간 중앙값 아래로 내려가는 비율을 측정한다. 두 검사는 서로 대체하지 않는다. 방법론 근거는 Bailey와 López de Prado의 [Deflated Sharpe Ratio 논문](https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf)과 Bailey 외 연구진의 [PBO/CSCV 논문](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2326253)이다.

## 상태 계약

| 상태 | 의미 | 자동 주문 |
|---|---|---|
| `INVALID_EVIDENCE` | hash, 후보, 봉인 영수증 또는 입력 정합성 실패 | 불가 |
| `REJECTED` | 완전한 증거가 경제성·위험·통계 게이트 실패 | 불가 |
| `INCOMPLETE` | 필요한 구간, 거래, 수익 행렬 또는 trial 원장이 부족 | 불가 |
| `FORWARD_VALIDATION_REQUIRED` | 역사 검증 통과, Shadow/Paper 증거 부족 | 불가 |
| `VERIFIED` | 역사·전진·운영 게이트 통과 | 별도 런타임 승인 artifact 필요 |

평가 리포트의 `automaticExecutionAllowed`는 상태와 무관하게 항상 `false`다. 실거래 활성화는 후속 단계에서 승인 리포트 fingerprint와 배포 이미지 fingerprint를 대조하는 별도 작업으로만 수행한다.

## 봉인 구간 소비

`config/research-sealed-registry-v1.json`은 봉인 프로토콜의 소비 상태를 기록한다. 현재 등록된 세 프로토콜은 모두 기존 실험에서 열람됐으므로 `CONSUMED_REJECTED`다.

- `volume-flow-sealed-windows-v1`
- `macro-donchian-sealed-windows-v1`
- `multi-horizon-momentum-validation-v1`

새 실험은 `AVAILABLE` 상태의 프로토콜만 봉인할 수 있다. 재생 결과에는 프로토콜 파일 hash, 후보 fingerprint, 재생 시각을 포함한 영수증이 있어야 한다. 실행 후 레지스트리가 동일 실험의 `CONSUMED_REJECTED` 또는 `CONSUMED_APPROVED`로 바뀌어도 기존 증거는 재검증할 수 있지만, 다른 실험은 이를 사용할 수 없다.

## 실행 방법

실험 정의를 먼저 커밋한 뒤 봉인한다.

```bash
node scripts/research-evidence.mjs seal \
  --definition=config/<experiment>.json \
  --out=build/research/<experiment>/manifest.json
```

재생 결과를 평가한다.

```bash
node scripts/research-evidence.mjs evaluate \
  --manifest=build/research/<experiment>/manifest.json \
  --run=build/research/<experiment>/run.json \
  --out=build/research/<experiment>/approval.json
```

기본 정책과 레지스트리는 각각 다음 파일이다.

- `config/research-approval-policy-v1.json`
- `config/research-sealed-registry-v1.json`

## 성공 기준

- 실험 입력 하나를 변경하면 기존 manifest 평가가 실패한다.
- 누적 trial 원장과 DSR 입력 개수가 다르면 승인되지 않는다.
- PBO 입력 후보 수와 선언된 단계 후보 수가 다르면 승인되지 않는다.
- 비용·위험 스트레스 조합 하나가 빠져도 `INCOMPLETE`다.
- 동일 partition·비용·위험 조건의 검증 구간이 겹치면 `INVALID_EVIDENCE`다.
- 이미 소비한 봉인 프로토콜은 새 실험에서 봉인되지 않는다.
- 역사 게이트를 모두 통과해도 Shadow/Paper 전진 증거 전에는 실거래가 허용되지 않는다.

## 위험과 후속 작업

- 현재 보유한 과거 데이터는 여러 차례 열람됐으므로 완전히 신선한 최종 봉인 표본이 아니다. M5에서는 nested walk-forward로 후보를 줄이되 이를 최종 실거래 승인과 구분한다.
- M7에서 새로 도착하는 Shadow/Paper 표본으로 수익 분포 drift와 실행 오차를 검증한다.
- M8에서 승인 리포트, 실행 계약, 전략 파라미터와 Docker 이미지 fingerprint가 모두 일치할 때만 최소 수량 실거래를 허용한다.
