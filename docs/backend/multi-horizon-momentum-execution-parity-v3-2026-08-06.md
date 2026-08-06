# 멀티 호라이즌 모멘텀 실행 패리티 v3

## 판정

`multi-horizon-momentum-development-v2`의 Node 연구기와 Kotlin 공통 엔진은 `causal-next-contiguous-open-v3` 계약으로 고정된 실제 BTCUSDT M5 구간의 거래 단위 패리티를 통과했다. 이는 두 엔진의 결과가 같다는 뜻일 뿐 수익성이나 실거래 승인을 의미하지 않는다. 프로필 상태는 `UNVERIFIED`, 자동 실행은 `false`다.

## v3 변경점

- 기존 stop과 target의 동일 봉 충돌은 계속 stop 우선으로 처리한다.
- trailing stop은 현재 봉의 기존 stop 체결 여부를 먼저 판정한 뒤 닫힌 봉으로 갱신한다.
- 새 trailing stop이 관측 가능한 종가와 같거나 종가를 넘어가면, 다음 봉의 더 유리한 stop 가격을 사용하지 않고 해당 종가에서 즉시 종료한다.
- 신호 다음의 연속된 M5 시가에 불리한 진입 슬리피지를 적용한다.
- 진입·종료 수수료와 슬리피지는 양쪽 모두 차감한다.

## 교차 검증

고정 입력은 `config/multi-horizon-momentum-parity-window-v3.json`이다.

| 항목 | 값 |
|---|---:|
| 워밍업 시작 | `2020-04-30T00:00:00Z` |
| 재생 구간 | `2020-05-30T00:00:00Z` ~ `2020-06-20T00:00:00Z` |
| Node 거래 | 5건 |
| Kotlin 거래 | 5건 |
| 비교 허용오차 | `0.00001` |
| 불일치 | 0건 |
| 판정 | `PASS` |

재현 명령:

```bash
node scripts/verify-multi-horizon-parity.mjs
```

생성 증거:

- `build/multi-horizon-momentum-parity-v3/node-trace.json`
- `build/multi-horizon-momentum-parity-v3/kotlin-trace.json`
- `build/multi-horizon-momentum-parity-v3/parity-report.json`

## 제한

이 단일 구간은 엔진 패리티 전용이다. 외부 구간 수익성, 비용 스트레스, 이익 집중도, bootstrap 기대값 하한을 증명하지 않는다. 기존 후보는 단일 이익 거래 집중도 제한을 통과하지 못했으므로 계속 `UNVERIFIED`다.
