# Maker shadow forward baseline 결과

## 판정

`maker_shadow_baseline_001`은 실제 주문을 전혀 제출하지 않고 Bybit 공개 WebSocket의 원시 orderbook·trade 이벤트만 사용한 개발용 shadow 실행에서 `REJECTED_DEVELOPMENT_BASELINE`으로 탈락했다. 실시간 실행과 봉인된 raw archive 재생 결과는 완전히 일치했지만, 비용 차감 전 손익부터 음수였고 13개 종료 포지션이 모두 손실이었다. 자동 주문 권한은 계속 `false`로 유지한다.

## 증거 범위

| 항목 | 결과 |
|---|---:|
| 수집 시각(UTC) | 2026-08-06 16:23:10 ~ 16:32:46 |
| 관측 시간 | 0.1600925시간 |
| 봉인 파일 | 10개 |
| raw 이벤트 | 22,887개 |
| 정규화 이벤트 | 29,831개 |
| sequence gap | 0개 |
| source snapshot SHA-256 | `b484a8cbad70d729f5c2284fe29632ef6346d921a2979fed78d62a3aecd63d16` |
| replay fingerprint | `099521e0cb90daa4f044df68b9ed21e89d17241fab22f7927fffd1f38d057f53` |

이 표본은 수익성을 주장하기에는 매우 짧다. 다만 실시간 엔진과 오프라인 재생기가 같은 이벤트에서 같은 결정을 만드는지 확인하는 실행 정합성 표본으로는 유효하다.

## 손익 결과

| 항목 | 결과 |
|---|---:|
| 시작 equity | 100 USDT |
| 종료 equity | 99.4394878345195 USDT |
| 순손익 | -0.5605121654805 USDT |
| 순수익률 | -0.5605121654805% |
| 최대 낙폭 | 0.5694121654805% |
| 종료 포지션 | 13건 |
| 수익 / 손실 | 0건 / 13건 |
| 비용 전 손익 | -0.19390861 USDT |
| maker 수수료 | 0.27776554 USDT |
| taker 수수료 | 0.0888380154805 USDT |
| 평균 1초 markout | -0.81478642 bps |
| 평균 5초 markout | -1.26117633 bps |
| 평균 30초 markout | -0.66292188 bps |
| 종료 시 inventory | 0.001 BTC |

종료 시 남은 inventory는 수집 프로세스를 임의 시점에 중단했기 때문이며 수익으로 간주하지 않는다. `closedInventory` 게이트는 실패했다.

## 실행 정합성

실시간 SQLite 원장과 raw archive 재생 결과는 다음 항목에서 정확히 일치했다.

- 원장 이벤트 4,984개
- 포지션 진입 14회, 종료 13회
- maker fill 20회, partial fill 10회
- 강제 taker 종료 4회
- 마지막 cash `34.8252378345195`
- 마지막 equity `99.4394878345195`
- 마지막 inventory `0.001 BTC`

따라서 현재 결과의 손실은 실시간 처리와 백테스트 구현 차이에서 생긴 것이 아니다. 동일한 보수적 queue 계약을 두 경로가 재현했고, baseline quote 정책 자체가 이 표본에서 음수 기대값을 만들었다.

## 원인

1. 비용 전 손익이 이미 `-0.19390861 USDT`다. 단순히 수수료를 낮춰도 양수 전략이 되지 않는다.
2. 평균 markout이 모든 관측 구간에서 음수다. 현재의 수동 체결은 유리한 체결보다 adverse selection을 더 많이 받았다.
3. 60초 최대 보유로 발생한 taker 종료 4회가 추가 비용을 만들었다.
4. spread가 존재하기만 하면 양방향 quote를 여는 baseline은 독립적인 방향 또는 독성 흐름 필터가 없다.
5. 9분 표본은 시장 국면 대표성이 없으므로 이 결과를 보고 수익 파라미터를 최적화하지 않는다.

## 게이트 결과

| 게이트 | 결과 |
|---|---:|
| 최소 168시간 관측 | 실패 |
| 최소 200개 종료 포지션 | 실패 |
| 종료 inventory 0 | 실패 |
| sequence gap 0 | 통과 |
| sealed file only | 통과 |
| queue stress | 미실행 |
| cost stress | 미실행 |
| 자동 실행 승인 | 금지 |

## 다음 단계

1. 같은 raw snapshot에 queue multiplier와 비용 multiplier를 적용하는 결정론적 stress matrix를 구현한다.
2. baseline을 수익 후보로 튜닝하지 않고 adverse-selection 진단 기준선으로 고정한다.
3. 다음 개발 후보는 사전 고정된 flow toxicity 조건과 inventory 정책을 사용하고 동일 raw snapshot에서 baseline과 비교한다.
4. 개발 후보가 비용 전·후 모두 양수이고 stress gate를 통과할 때만 장기 forward evidence 수집 대상으로 승격한다.
5. 외부/봉인 데이터는 개발 후보와 임계값을 고정하기 전에는 열람하지 않는다.

현재 상태는 **실시간 이벤트 실행과 재생 정합성은 통과했지만 수익 전략은 없음**이다.
