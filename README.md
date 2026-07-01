# Bybit Trader

Initial public repository placeholder for a Bybit trading automation project.

## Status

Milestone 1 is the operational backend shell:

- Kotlin/JVM Gradle multi-module project.
- Ktor server with public health and private status/control APIs.
- SQLDelight + SQLite event ledger for bot state, control events, alerts, and
  public market candles, and future trading records.
- Bybit public REST kline sync through a protected operator endpoint.
- Backtest endpoint over stored candles with fee, slippage, funding, partial
  take-profit, breakeven stop, ATR trailing, drawdown, expectancy, no-trade
  reasons, compounding score, return-to-drawdown ratio, and estimated return
  metrics.
- Paper evaluation endpoint that records strategy signals, paper market orders,
  fills, positions, and performance snapshots without private Bybit order calls.
- Volume-flow composite backtest endpoint that replays multiple strategy legs on
  one equity curve with overlap, daily stop, trade-count controls, and monthly
  and walk-forward performance summaries. Volume-flow responses include
  `compoundDailyReturnPct`, `averageWinR`, `averageLossR`, `payoffRatio`,
  `breakevenWinRatePct`, `winRateEdgePct`, and `activeDayCoveragePct` so daily
  targets are evaluated on a compounding, coverage, and asymmetric-expectancy
  basis.
- Telegram and Discord webhook alert sink wiring, disabled unless configured.
- Paper mode starts without Bybit private credentials.

No live or testnet exchange trading is implemented yet.

## Local Run

```bash
export BOT_CONTROL_TOKEN="replace-with-local-operator-token"
export BOT_DATABASE_PATH="$PWD/data/bybit-trader.sqlite"
export BOT_SYMBOL="BTCUSDT"
export BOT_TIMEFRAMES="M1,M5,M15"
export BOT_PAPER_LOOP_ENABLED="false"
./gradlew :modules:bot-app:run
```

Set `BOT_PAPER_LOOP_ENABLED=true` to run the paper loop automatically. Optional
loop settings are `BOT_PAPER_TIMEFRAME`, `BOT_PAPER_CANDLE_LIMIT`, and
`BOT_PAPER_INTERVAL_SECONDS`.

Smoke test:

```bash
curl http://127.0.0.1:8080/health
curl -H "Authorization: Bearer $BOT_CONTROL_TOKEN" http://127.0.0.1:8080/status
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframes":["M1","M5","M15"],"limit":200}' \
  http://127.0.0.1:8080/market-data/sync
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframes":["M1","M5","M15"],"daysBack":365,"pageLimit":1000,"maxRequestsPerTimeframe":1000}' \
  http://127.0.0.1:8080/market-data/history/sync
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M15","candleLimit":200,"fundingRatePer8h":0.0,"partialTakeProfitR":1.0,"partialTakeProfitFraction":0.5,"breakevenAfterPartialTakeProfit":true,"atrTrailingMultiplier":0.0}' \
  http://127.0.0.1:8080/backtests/run
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M15","candleLimit":200,"oversoldRsiValues":[25.0,30.0,35.0],"overboughtRsiValues":[65.0,70.0,75.0],"topResults":5}' \
  http://127.0.0.1:8080/backtests/mean-reversion/sweep
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","m1Limit":525600,"m5Limit":105120,"m15Limit":35040,"riskFraction":0.02,"setupMode":"BREAKOUT_CONTINUATION","entryMode":"RETEST_CONFIRMATION","sideMode":"BOTH","setupTimeframe":"M5","relativeVolumeThreshold":5.0,"volumeZScoreThreshold":1.5,"requireM5Vwap":false,"requireContextVwap":true,"requireContextTrend":true,"allowedMarketRegimes":["TREND_DOWN"],"requireRegimeSideAlignment":true,"requireKeyLevelProximity":true,"keyLevelTolerancePct":0.0025,"avoidRangeMiddle":true,"minEntryRiskPct":0.008,"maxEntryRiskPct":0.015,"maxEstimatedFeeR":0.2,"targetR":1.2,"exitMode":"FIXED_TARGET","breakevenTriggerR":null,"maxHoldM1Candles":30,"dailyTargetPct":null,"dailyStopPct":1.0,"minTradesPerDay":1,"maxTradesPerDay":5}' \
  http://127.0.0.1:8080/backtests/volume-flow/run
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","m1Limit":525600,"m5Limit":105120,"m15Limit":35040,"initialEquity":10000.0,"dailyTargetPct":null,"dailyStopPct":1.0,"maxConsecutiveLosses":3,"legs":[{"id":"trend_down_retest","riskFraction":0.02,"setupMode":"BREAKOUT_CONTINUATION","entryMode":"RETEST_CONFIRMATION","setupTimeframe":"M5","relativeVolumeThreshold":5.0,"volumeZScoreThreshold":1.5,"setupRangeLookback":12,"requireM5Vwap":false,"requireContextVwap":true,"requireContextTrend":true,"allowedMarketRegimes":["TREND_DOWN"],"requireRegimeSideAlignment":true,"requireKeyLevelProximity":true,"keyLevelTolerancePct":0.0025,"avoidRangeMiddle":true,"minBodyRatio":0.45,"minDirectionalCloseStrength":0.70,"minEntryRiskPct":0.008,"maxEntryRiskPct":0.015,"targetR":1.2,"exitMode":"FIXED_TARGET","maxHoldM1Candles":30},{"id":"trend_down_close","riskFraction":0.02,"setupMode":"BREAKOUT_CONTINUATION","entryMode":"CLOSE_CONFIRMATION","setupTimeframe":"M5","relativeVolumeThreshold":3.5,"volumeZScoreThreshold":0.5,"setupRangeLookback":8,"requireM5Vwap":false,"requireContextVwap":true,"requireContextTrend":true,"allowedMarketRegimes":["TREND_DOWN"],"requireRegimeSideAlignment":true,"requireKeyLevelProximity":true,"keyLevelTolerancePct":0.0025,"avoidRangeMiddle":true,"minBodyRatio":0.55,"minDirectionalCloseStrength":0.70,"minEntryRiskPct":0.008,"maxEntryRiskPct":0.015,"targetR":1.2,"exitMode":"FIXED_TARGET","maxHoldM1Candles":15},{"id":"range_failed_break","riskFraction":0.02,"setupMode":"FAILED_BREAK_REVERSAL","entryMode":"CLOSE_CONFIRMATION","setupTimeframe":"M5","relativeVolumeThreshold":3.5,"volumeZScoreThreshold":0.5,"setupRangeLookback":12,"requireM5Vwap":false,"requireContextVwap":false,"requireContextTrend":false,"allowedMarketRegimes":["RANGE"],"requireRegimeSideAlignment":false,"requireKeyLevelProximity":true,"keyLevelTolerancePct":0.0025,"avoidRangeMiddle":true,"minBodyRatio":0.25,"minDirectionalCloseStrength":0.70,"minRejectionWickRatio":0.25,"entryLookaheadM1Candles":5,"minEntryRiskPct":0.008,"maxEntryRiskPct":0.015,"targetR":1.0,"exitMode":"FIXED_TARGET","maxHoldM1Candles":30}]}' \
  http://127.0.0.1:8080/backtests/volume-flow/composite/run
# Add "tradeLimit":10000 to the composite body when full accepted-trade
# output is needed for tuning analysis. Set "maxConcurrentPositions" when
# testing concurrent futures-style execution; the default is 1.
# The default response returns the latest 50 trades.
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","m1Limit":525600,"m5Limit":105120,"m15Limit":35040,"riskFractionValues":[0.0075,0.015,0.02],"setupModes":["BREAKOUT_CONTINUATION"],"entryModes":["RETEST_CONFIRMATION"],"sideModes":["BOTH"],"setupTimeframes":["M5"],"relativeVolumeThresholdValues":[3.5,5.0],"volumeZScoreThresholdValues":[0.5,1.5],"setupRangeLookbackValues":[8,12],"requireM5VwapValues":[false],"requireContextVwapValues":[true],"requireContextTrendValues":[true],"allowedMarketRegimeValues":[["TREND_DOWN"]],"requireRegimeSideAlignmentValues":[true],"requireKeyLevelProximityValues":[true],"avoidRangeMiddleValues":[true],"minEntryRiskPctValues":[null,0.008,0.01],"maxEntryRiskPctValues":[null,0.015,0.02],"targetRValues":[1.0,1.2],"exitModes":["FIXED_TARGET"],"breakevenTriggerRValues":[null],"maxHoldM1CandlesValues":[15,30],"dailyTargetPct":null,"maxCandidates":1000,"topResults":10}' \
  http://127.0.0.1:8080/backtests/volume-flow/sweep
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M15","candleLimit":200}' \
  http://127.0.0.1:8080/paper/evaluate
curl -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  "http://127.0.0.1:8080/signals/recent?limit=10"
curl -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  "http://127.0.0.1:8080/trades/recent?limit=10"
```

Run verification before committing:

```bash
./gradlew format
./gradlew test lint build
node .opendock/harness/opendock__backend-ultrawork/check.mjs
node .opendock/harness/opendock__business-ultrawork/check.mjs
```

## Notes

- Do not commit API keys, secrets, or local environment files.
- Keep exchange credentials in local environment variables or a secrets manager.
- Latest local BTCUSDT volume-flow snapshot: the current composite candidate in
  `config/volume-flow-composite-current.json` uses `riskFraction=0.075` and
  applies an 8-candle, `0.45R` follow-through check to the two M5 trend-down
  fixed-target legs. The `trend_down_close` leg also moves to breakeven after
  `0.65R`. On the local three-year dataset, the replay returned `18,871.26%`
  net return, `0.47931%` compound daily return, `33.29%` realized max drawdown,
  and `34.21%` mark-to-market max drawdown over 270 trades.
  This is improved but still below the `0.84390%` compound daily return required
  for `1,000,000 KRW -> 10,000,000,000 KRW` over three years. See
  `docs/backend/volume-flow-multi-year-growth-report.md` for the multi-year
  reproduction notes, accepted/rejected tuning paths, and the next improvement
  list. Source note: measured on
  `build/runtime-test/bybit-trader-3y-backtest.sqlite` covering
  `2023-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`; this is not a live-trading
  return guarantee.

<!-- OPENDOCK:START id=files:README.md dock=opendock/business-ultrawork path=README.md -->
# Business Ultrawork

PRD, user story, GTM, ICP, pricing, marketing claim, 근거 자료, release note를 확인하는 비즈니스 품질 게이트입니다.

## 확인하는 것

- PRD에는 문제, 목표, 제외 범위, 성공 지표, 리스크, 요구사항이 있어야 합니다.
- user story에는 acceptance criteria가 있어야 합니다.
- GTM 문서에는 ICP, 채널, 가격, 포지셔닝이 있어야 합니다.
- 마케팅 문구에는 명확한 CTA가 있어야 합니다.
- 주장에는 근거 또는 출처 메모가 필요합니다.
- release note에는 필요할 때 breaking change와 migration note가 포함되어야 합니다.

PM, founder, marketing 산출물의 품질을 집중적으로 점검해야 하는 workspace에 사용합니다.
<!-- OPENDOCK:END id=files:README.md dock=opendock/business-ultrawork path=README.md -->

<!-- OPENDOCK:START id=files:README.md dock=opendock/backend-ultrawork path=README.md -->
# Backend Ultrawork

API 계약, 검증, 인증, 마이그레이션, 로깅, 서비스 안전성을 확인하는 백엔드 품질 게이트입니다.

## 확인하는 것

- 백엔드 서비스에 formatter, lint, test, build가 준비되어 있어야 합니다.
- request body는 사용하기 전에 검증해야 합니다.
- 인증이 필요한 endpoint에는 명시적인 guard가 있어야 합니다.
- 하드코딩된 secret과 민감정보 로깅을 막습니다.
- 데이터베이스 마이그레이션은 dry-run과 rollback을 고려해야 합니다.
- OpenAPI나 schema 문서가 실제 route와 어긋나면 안 됩니다.

백엔드 API와 서비스 품질을 집중적으로 점검해야 하는 workspace에 사용합니다.
<!-- OPENDOCK:END id=files:README.md dock=opendock/backend-ultrawork path=README.md -->
