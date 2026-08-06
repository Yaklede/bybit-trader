<!-- OPENDOCK:START id=files:docs/backend/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/README.md -->
# Backend Artifacts

## 목표

`docs/backend`는 백엔드 TDD, API spec, ADR, 참고 자료 같은 백엔드 산출물의 홈이다.

현재 유효한 백엔드 규칙 Source of Truth는 [docs/rules/backend](../rules/backend/README.md)가 소유한다.

## 문서 맵

| 영역 | 경로 | 책임 |
|------|------|------|
| Backend rules | [../rules/backend/README.md](../rules/backend/README.md) | 백엔드 작업에서 반드시 따라야 하는 규칙 |
| TDD | [tdd/README.md](tdd/README.md) | 기능 또는 변경 단위 기술 설계 산출물 |
| API specs | [api-specs/README.md](api-specs/README.md) | HTTP API 계약과 frontend 연동 계약 |
| ADR | [adr/README.md](adr/README.md) | 장기 보존할 백엔드 아키텍처 의사결정 |
| Volume-flow aggressive risks | [volume-flow-aggressive-risk-register.md](volume-flow-aggressive-risk-register.md) | 공격형 전략 운영 전 후속 관리할 리스크 목록 |
| Volume-flow production readiness | [volume-flow-production-readiness-plan.md](volume-flow-production-readiness-plan.md) | 공격형 전략을 온프레미스 운영 봇으로 전환하기 위한 마일스톤 |
| Forward-flow backtest design | [forward-flow-backtest-design-2026-07-11.md](forward-flow-backtest-design-2026-07-11.md) | 순방향 호가 흐름만으로 수행하는 인과 백테스트의 범위와 승격 게이트 |
| Actual-fill protection contract | [actual-fill-protection-contract-2026-08-06.md](actual-fill-protection-contract-2026-08-06.md) | 실제 체결가 기준 TP/SL 재계산, 검증, fail-closed 실행 계약 |
| Causal paper execution contract | [causal-paper-execution-contract-2026-08-06.md](causal-paper-execution-contract-2026-08-06.md) | 백테스트와 동일한 다음 봉 진입·포지션 정책을 사용하는 영속 Paper 실행 계약 |
| Research evidence contract | [research-evidence-contract-2026-08-06.md](research-evidence-contract-2026-08-06.md) | 실험 fingerprint, sealed 소비, bootstrap·DSR·PBO와 전진 검증 승인 계약 |
| Volume-confirmed trend protocol | [volume-confirmed-trend-ensemble-v1-protocol-2026-08-07.md](volume-confirmed-trend-ensemble-v1-protocol-2026-08-07.md) | H4 거래량 확인형 멀티 호라이즌 후보의 고정 계산·비용·승인 계약 |
| Volume-confirmed trend development | [volume-confirmed-trend-ensemble-v1-development-result-2026-08-07.md](volume-confirmed-trend-ensemble-v1-development-result-2026-08-07.md) | Bybit 개발 구간 결과와 외부 검증 전 판정 |
| Volume-confirmed trend external | [volume-confirmed-trend-ensemble-v1-external-result-2026-08-07.md](volume-confirmed-trend-ensemble-v1-external-result-2026-08-07.md) | 첫 열람 Binance USD-M 외부·비용·자본 스트레스 결과 |
| Volume-confirmed trend core parity | [volume-confirmed-trend-ensemble-v1-kotlin-parity-2026-08-07.md](volume-confirmed-trend-ensemble-v1-kotlin-parity-2026-08-07.md) | Node 연구기와 Kotlin 공통 계산 코어의 거래 단위 패리티 |
| Volume-confirmed trend runtime parity | [volume-confirmed-trend-ensemble-v1-runtime-parity-2026-08-07.md](volume-confirmed-trend-ensemble-v1-runtime-parity-2026-08-07.md) | 역사 어댑터와 영속 Shadow 런타임의 전체 재생 패리티 및 전진 기준 |
| Volume-confirmed trend container smoke | [volume-confirmed-trend-ensemble-v1-container-smoke-2026-08-07.md](volume-confirmed-trend-ensemble-v1-container-smoke-2026-08-07.md) | 주문을 차단한 컨테이너에서 public-data Shadow, API, 대시보드를 검증한 결과 |
| Volume-confirmed trend live execution TDD | [tdd/volume-confirmed-trend-live-execution.md](tdd/volume-confirmed-trend-live-execution.md) | 승인 후 H4 목표 포지션을 실제 주문으로 전환할 상태 머신·원장·복구 설계 |
| Volume-impact state development | [volume-impact-state-development-protocol-2026-08-06.md](volume-impact-state-development-protocol-2026-08-06.md) | 거래량-가격충격 지속형·소진 반전형의 인과적 M15→M5→M1 nested walk-forward 계약 |
| Volume-impact state result | [volume-impact-state-development-result-2026-08-06.md](volume-impact-state-development-result-2026-08-06.md) | 24개 사전 고정 후보의 탈락 결과와 다음 독립 가설 근거 |
| Volume-structure development v2 | [volume-structure-development-v2-protocol-2026-08-06.md](volume-structure-development-v2-protocol-2026-08-06.md) | 돌파 재시험 지속형과 2봉 군집 소진 반전형의 사전 고정 개발 계약 |
| Volume-structure v2 result | [volume-structure-development-v2-result-2026-08-06.md](volume-structure-development-v2-result-2026-08-06.md) | 재시험 지속형 폐기와 군집 반전의 소표본 방향 비대칭 진단 |
| Asymmetric cluster absorption v3 | [asymmetric-cluster-absorption-v3-protocol-2026-08-06.md](asymmetric-cluster-absorption-v3-protocol-2026-08-06.md) | 방향별 거래량 상태와 active-month 평가를 고정한 단일 12후보 계약 |
| Asymmetric cluster v3 result | [asymmetric-cluster-absorption-v3-result-2026-08-06.md](asymmetric-cluster-absorption-v3-result-2026-08-06.md) | 가족 게이트 탈락 결과와 승격 불가 단일 역사 진단 후보 |
| Asymmetric cluster 2024+ diagnostic | [asymmetric-cluster-post2024-diagnostic-protocol-2026-08-06.md](asymmetric-cluster-post2024-diagnostic-protocol-2026-08-06.md) | 고정 후보의 재사용 역사 10분기·비용 1~2배 반증 계약 |
| On-prem paper deployment | [on-prem-paper-deployment-runbook.md](on-prem-paper-deployment-runbook.md) | Twingate 뒤에서 인과적 paper loop를 운영하기 위한 배포 직전 절차 |

## 규칙

- BACKEND-ARTIFACT-001: `docs/backend` 하위 문서는 백엔드 산출물과 참고 자료만 소유한다.
- BACKEND-ARTIFACT-002: 백엔드 규칙 원문은 `docs/rules/backend`에 둔다.
- BACKEND-ARTIFACT-003: TDD, API spec, ADR은 필요한 경우 `docs/rules/backend` 규칙을 Source of Truth로 참조한다.
- BACKEND-ARTIFACT-004: `docs/backend` 산출물은 `docs/rules/backend` 규칙과 충돌하면 안 된다.
<!-- OPENDOCK:END id=files:docs/backend/README.md dock=wooyongdev/backend-engineering-kit path=docs/backend/README.md -->
