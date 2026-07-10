import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  CircleStop,
  History,
  LayoutDashboard,
  Moon,
  PauseCircle,
  PlayCircle,
  RefreshCw,
  RotateCcw,
  Send,
  Settings2,
  SlidersHorizontal,
  Sun,
  Zap,
} from "lucide-react";
import "./styles.css";

const STORAGE_KEYS = {
  apiBase: "bybit-dashboard-api-base",
  accessKey: "bybit-dashboard-access-key",
  theme: "bybit-dashboard-theme",
};

const VIEW_ITEMS = [
  { id: "overview", label: "개요", icon: LayoutDashboard },
  { id: "trading", label: "매매", icon: BarChart3 },
  { id: "activity", label: "활동", icon: History },
  { id: "settings", label: "설정/진단", icon: Settings2 },
];

const POLL_TICK_MS = 15000;
const OVERVIEW_SUMMARY_REFRESH_MS = 60000;
const CONNECTION_SETTINGS_TITLE = "연결 설정";
const ATTENTION_DATA_STATE = "error";
const SMOKE_ATTENTION_STATUS = "FAIL";
const SYNC_STOPPED_STATUS = "FAILED";
const PERFORMANCE_REFRESH_MS = 60000;
const ACTIVITY_SUMMARY_REFRESH_MS = 60000;
const CLOSED_TRADES_REFRESH_MS = 30000;
const SYNC_STATUS_REFRESH_MS = 30000;
const STRATEGY_PROFILE_REFRESH_MS = 300000;

const EMPTY_SUMMARY = {
  runtimeMode: "",
  executionAvailable: false,
  bot: { mode: "확인 필요", updatedAt: "", heartbeatAt: "" },
  forwardMarketCapture: { enabled: false, orderBookFresh: false, latestOrderBookBarAt: "", latestLiquidationBarAt: "" },
  recentSignals: [],
  recentTrades: [],
};

const EMPTY_STRATEGY_STATE = {
  activeProfileId: "",
  runtimeProfileId: "",
  profiles: [],
};

const EMPTY_MOBILE_SUMMARY = {
  runtimeMode: "",
  bot: { mode: "확인 필요", updatedAt: "", heartbeatAt: "" },
  marketSync: false,
  livePerformance: false,
  recentClosedTrades: [],
  recentSignals: [],
  alerts: {},
};

function App() {
  const [apiBase, setApiBase] = useState(() => localStorage.getItem(STORAGE_KEYS.apiBase) || "/api");
  const [accessKey, setAccessKey] = useState(() => localStorage.getItem(STORAGE_KEYS.accessKey) || "");
  const [apiBaseDraft, setApiBaseDraft] = useState(() => localStorage.getItem(STORAGE_KEYS.apiBase) || "/api");
  const [accessKeyDraft, setAccessKeyDraft] = useState(() => localStorage.getItem(STORAGE_KEYS.accessKey) || "");
  const [theme, setTheme] = useState(resolveInitialTheme);
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [coin, setCoin] = useState("USDT");
  const [symbolDraft, setSymbolDraft] = useState("BTCUSDT");
  const [coinDraft, setCoinDraft] = useState("USDT");
  const [activeView, setActiveView] = useState("overview");
  const [summary, setSummary] = useState(EMPTY_SUMMARY);
  const [strategyState, setStrategyState] = useState(EMPTY_STRATEGY_STATE);
  const [mobileSummary, setMobileSummary] = useState(EMPTY_MOBILE_SUMMARY);
  const [livePerformance, setLivePerformance] = useState(false);
  const [closedTrades, setClosedTrades] = useState([]);
  const [closedTradeCursor, setClosedTradeCursor] = useState("");
  const [syncStatus, setSyncStatus] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);
  const [problemMessage, setProblemMessage] = useState("");
  const [notice, setNotice] = useState("");
  const [smokeResult, setSmokeResult] = useState();
  const [smokeQuantity, setSmokeQuantity] = useState("0.001");
  const [smokeSide, setSmokeSide] = useState("BUY");
  const initialSummaryKeyRef = useRef("");
  const lastFetchedAtRef = useRef({});
  const pollInFlightRef = useRef(false);
  const connectionSettingsChanged =
    apiBaseDraft !== apiBase || accessKeyDraft !== accessKey || symbolDraft !== symbol || coinDraft !== coin;

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem(STORAGE_KEYS.theme, theme);
  }, [theme]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.apiBase, apiBase);
  }, [apiBase]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.accessKey, accessKey);
  }, [accessKey]);

  const client = useMemo(
    () => ({
      request: async (path, options = {}) => {
        if (!accessKey.trim()) {
          throw { message: "운영 접근키를 입력한 뒤 다시 시도해 주세요." };
        }
        const response = await fetch(`${apiBase.replace(/\/$/, "")}${path}`, {
          ...options,
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${accessKey.trim()}`,
            ...(options.headers || {}),
          },
        });
        const responseText = await response.text();
        const responseBody = parseJsonBody(responseText);
        if (!response.ok) {
          throw {
            message: `${resolveFailureMessage(responseBody)} 접근키와 API 주소를 확인한 뒤 다시 시도해 주세요.`,
            responseBody,
          };
        }
        return responseBody || {};
      },
    }),
    [accessKey, apiBase],
  );

  const fetchScope = useCallback(
    async (scope) => {
      let value;
      if (scope === "summary") {
        value = await client.request(
          `/dashboard/summary?symbol=${encodeURIComponent(symbol)}&coin=${encodeURIComponent(coin)}&limit=20`,
        );
        setSummary(value || EMPTY_SUMMARY);
      } else if (scope === "profiles") {
        value = await client.request("/strategy/profiles");
        setStrategyState(value || EMPTY_STRATEGY_STATE);
      } else if (scope === "mobile") {
        value = await client.request(
          `/dashboard/mobile-summary?symbol=${encodeURIComponent(symbol)}&coin=${encodeURIComponent(coin)}&tradeLimit=20&signalLimit=20`,
        );
        setMobileSummary({
          ...EMPTY_MOBILE_SUMMARY,
          ...(value || {}),
          bot: { ...EMPTY_MOBILE_SUMMARY.bot, ...(value?.bot || {}) },
        });
      } else if (scope === "performance") {
        value = await client.request("/performance/live/summary?window=all");
        setLivePerformance(value || false);
      } else if (scope === "closedTrades") {
        value = await client.request(`/execution/closed-trades?symbol=${encodeURIComponent(symbol)}&limit=20`);
        setClosedTrades(value?.items || []);
        setClosedTradeCursor(value?.nextCursor || "");
      } else if (scope === "sync") {
        value = await client.request(`/market-data/closed-candle/status?symbol=${encodeURIComponent(symbol)}`);
        setSyncStatus(value?.checkpoints || []);
      } else {
        throw new TypeError(`지원하지 않는 데이터 범위예요: ${scope}. 화면을 새로고침한 뒤 다시 시도해 주세요.`);
      }
      lastFetchedAtRef.current[scope] = Date.now();
      return value;
    },
    [client, coin, symbol],
  );

  const refreshData = useCallback(
    async (
      scopes,
      { silent = false, clearFeedback = true, reportProblems = !silent, successMessage = "" } = {},
    ) => {
      const uniqueScopes = [...new Set(scopes)];
      if (uniqueScopes.length === 0) {
        return { fulfilled: 0, rejected: 0 };
      }
      if (!silent) {
        setIsLoading(true);
      }
      if (clearFeedback) {
        setProblemMessage("");
        setNotice("");
      }
      try {
        const results = await Promise.allSettled(uniqueScopes.map((scope) => fetchScope(scope)));
        const fulfilled = results.filter((result) => result.status === "fulfilled").length;
        const rejectedResults = results.filter((result) => result.status === "rejected");
        if (fulfilled > 0) {
          setHasLoaded(true);
        }
        if (rejectedResults.length > 0 && reportProblems) {
          setProblemMessage(
            rejectedResults[0].reason?.message || "데이터를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.",
          );
        }
        if (successMessage && fulfilled > 0 && rejectedResults.length === 0) {
          setNotice(successMessage);
        }
        return { fulfilled, rejected: rejectedResults.length };
      } finally {
        if (!silent) {
          setIsLoading(false);
        }
      }
    },
    [fetchScope],
  );

  const refreshActiveView = useCallback(
    () => {
      if (activeView === "settings" && connectionSettingsChanged) {
        setApiBase(apiBaseDraft);
        setAccessKey(accessKeyDraft);
        setSymbol(symbolDraft);
        setCoin(coinDraft);
        return Promise.resolve({ fulfilled: 0, rejected: 0 });
      }
      return refreshData(manualRefreshScopes(activeView), {
        successMessage: `${viewLabel(activeView)} 데이터를 새로고침했어요.`,
      });
    },
    [
      accessKeyDraft,
      activeView,
      apiBaseDraft,
      coinDraft,
      connectionSettingsChanged,
      refreshData,
      symbolDraft,
    ],
  );

  useEffect(() => {
    if (!accessKey.trim()) {
      return;
    }
    const connectionKey = `${apiBase}|${accessKey.trim()}|${symbol}|${coin}`;
    const isInitialConnection = initialSummaryKeyRef.current !== connectionKey;
    if (isInitialConnection) {
      initialSummaryKeyRef.current = connectionKey;
      lastFetchedAtRef.current = {};
    }
    const scopes = activationScopes(activeView);
    if (activeView === "overview" && isInitialConnection) {
      scopes.push("summary");
    }
    if (activeView === "overview" && scopeIsDue(lastFetchedAtRef.current, "summary", OVERVIEW_SUMMARY_REFRESH_MS)) {
      scopes.push("summary");
    }
    if (activeView === "overview" && scopeIsDue(lastFetchedAtRef.current, "performance", PERFORMANCE_REFRESH_MS)) {
      scopes.push("performance");
    }
    if (
      (activeView === "overview" || activeView === "trading") &&
      scopeIsDue(lastFetchedAtRef.current, "profiles", STRATEGY_PROFILE_REFRESH_MS)
    ) {
      scopes.push("profiles");
    }
    void refreshData(scopes, { clearFeedback: true });
  }, [accessKey, activeView, apiBase, coin, refreshData, symbol]);

  useEffect(() => {
    if (!accessKey.trim()) {
      return () => {};
    }
    const timer = window.setInterval(async () => {
      if (document.visibilityState === "hidden" || pollInFlightRef.current) {
        return;
      }
      const scopes = pollingScopes(activeView, lastFetchedAtRef.current);
      if (scopes.length === 0) {
        return;
      }
      pollInFlightRef.current = true;
      try {
        await refreshData(scopes, { silent: true, clearFeedback: false, reportProblems: false });
      } finally {
        pollInFlightRef.current = false;
      }
    }, POLL_TICK_MS);
    return () => window.clearInterval(timer);
  }, [accessKey, activeView, apiBase, coin, refreshData, symbol]);

  const applyBotModeFromResult = useCallback((result) => {
    const nextMode = result?.newMode || result?.resumeMode || result?.pauseMode;
    if (!nextMode) {
      return;
    }
    const updatedAt = new Date().toISOString();
    setMobileSummary((current) => ({
      ...current,
      capturedAt: updatedAt,
      bot: { ...current.bot, mode: nextMode, updatedAt },
    }));
  }, []);

  const runAction = useCallback(
    async (label, path, body, refreshScopes = ["mobile"]) => {
      setIsLoading(true);
      setProblemMessage("");
      setNotice("");
      try {
        const result = await client.request(path, {
          method: "POST",
          body: JSON.stringify(body),
        });
        applyBotModeFromResult(result);
        const refreshResult = await refreshData(refreshScopes, {
          silent: true,
          clearFeedback: false,
          reportProblems: true,
        });
        setNotice(
          refreshResult.rejected > 0
            ? `${label} 요청은 처리됐지만 일부 상태를 다시 확인하지 못했어요.`
            : `${label} 요청을 처리하고 관련 상태를 갱신했어요.`,
        );
        return result;
      } catch (nextProblem) {
        setProblemMessage(nextProblem.message || "요청을 마치지 못했어요. 접근키, API 주소, 서버 상태를 확인한 뒤 다시 시도해 주세요.");
        return false;
      } finally {
        setIsLoading(false);
      }
    },
    [applyBotModeFromResult, client, refreshData],
  );

  const runSmokeAction = useCallback(
    async (label, path, body, options = {}) => {
      if (options.confirmMessage && !window.confirm(options.confirmMessage)) {
        return false;
      }
      setIsLoading(true);
      setProblemMessage("");
      setNotice("");
      setSmokeResult({ label, status: "RUNNING" });
      try {
        const result = await client.request(path, {
          method: "POST",
          body: JSON.stringify(body),
        });
        setSmokeResult({ label, status: "PASS", responseBody: result });
        applyBotModeFromResult(result);
        const refreshResult = await refreshData(options.refreshScopes || [], {
          silent: true,
          clearFeedback: false,
          reportProblems: true,
        });
        setNotice(
          refreshResult.rejected > 0
            ? `${label} 테스트는 완료됐지만 일부 상태를 다시 확인하지 못했어요.`
            : `${label} 테스트가 완료됐어요.`,
        );
        return result;
      } catch (nextProblem) {
        setSmokeResult({
          label,
          status: SMOKE_ATTENTION_STATUS,
          ...(nextProblem.responseBody ? { responseBody: nextProblem.responseBody } : {}),
        });
        setProblemMessage(nextProblem.message || `${label} 테스트를 마치지 못했어요.`);
        return false;
      } finally {
        setIsLoading(false);
      }
    },
    [applyBotModeFromResult, client, refreshData],
  );

  const activateStrategyProfile = useCallback(
    async (profile) => {
      setIsLoading(true);
      setProblemMessage("");
      setNotice("");
      try {
        const result = await client.request("/strategy/profiles/active", {
          method: "POST",
          body: JSON.stringify({ profileId: profile.id }),
        });
        setStrategyState(result);
        setNotice(`${profile.name} 프로필을 적용했어요.`);
        return result;
      } catch (nextProblem) {
        setProblemMessage(nextProblem.message || "전략 프로필을 바꾸지 못했어요. 접근키와 서버 상태를 확인한 뒤 다시 시도해 주세요.");
        return false;
      } finally {
        setIsLoading(false);
      }
    },
    [client],
  );

  const account = summary.account;
  const market = summary.market;
  const reconciliation = summary.reconciliation;
  const positions = reconciliation?.positions || [];
  const openOrders = reconciliation?.openOrders || [];
  const executions = reconciliation?.executions || [];
  const activitySignals = mobileSummary.recentSignals?.length ? mobileSummary.recentSignals : summary.recentSignals || [];
  const activityClosedTrades = closedTrades.length ? closedTrades : mobileSummary.recentClosedTrades || [];
  const dataState = problemMessage ? "error" : hasLoaded ? "ready" : "idle";

  const runtimeMode = mobileSummary.runtimeMode || summary.runtimeMode;
  const bot = mostRecentBot(summary.bot, mobileSummary.bot);
  const strategyProfiles = strategyState.profiles || [];
  const activeProfile = strategyState.activeProfile;
  const runtimeProfile = strategyState.runtimeProfile;
  const strategyEvaluationEnabled = activeProfile?.runtimeEligible !== false;
  const manualExchangeEnabled = isManualRuntime(runtimeMode) && summary.executionAvailable;

  const primarySync = mobileSummary.marketSync || syncStatus[0] || false;
  const primaryPerformance =
    livePerformance || mobileSummary.livePerformance
      ? { ...(livePerformance || {}), ...(mobileSummary.livePerformance || {}) }
      : false;
  const latestSignal = activitySignals[0];
  const lastCapturedAt = mobileSummary.capturedAt || account?.capturedAt || summary.bot?.updatedAt;
  const forwardMarketCapture = summary.forwardMarketCapture || EMPTY_SUMMARY.forwardMarketCapture;

  const runManualMarketOrder = useCallback(
    () =>
      runSmokeAction(
        "수동 시장가 주문",
        "/execution/manual/market-order",
        {
          symbol,
          side: smokeSide,
          quantity: smokeQuantity,
          acknowledgement: manualAcknowledgement(runtimeMode, "MARKET_ORDER"),
        },
        {
          confirmMessage:
            `${formatRuntime(runtimeMode)} 계정에 ${symbol} ${formatSide(smokeSide)} ` +
            `${smokeQuantity} 시장가 주문을 전송합니다. 계속할까요?`,
          refreshScopes: ["summary", "mobile"],
        },
      ),
    [runSmokeAction, runtimeMode, smokeQuantity, smokeSide, symbol],
  );

  const runCancelOrder = useCallback(
    (order) =>
      runSmokeAction(
        "주문 취소",
        "/execution/orders/cancel",
        {
          symbol: order.symbol,
          exchangeOrderId: order.exchangeOrderId,
          clientOrderId: order.clientOrderId,
        },
        {
          confirmMessage: `${order.symbol} 미체결 주문 ${shortId(order.exchangeOrderId || order.clientOrderId)} 취소 요청을 보낼까요?`,
          refreshScopes: ["summary", "mobile"],
        },
      ),
    [runSmokeAction],
  );

  const runClosePosition = useCallback(
    (position) =>
      runSmokeAction(
        "포지션 청산",
        "/execution/manual/close-position",
        {
          symbol: position.symbol,
          positionSide: position.side,
          quantity: position.size,
          acknowledgement: manualAcknowledgement(runtimeMode, "CLOSE_POSITION"),
        },
        {
          confirmMessage:
            `${formatRuntime(runtimeMode)} 계정에서 ${position.symbol} ${formatSide(position.side)} 포지션 ` +
            `${position.size}을 포지션 감소 전용 시장가로 청산합니다. 계속할까요?`,
          refreshScopes: ["summary", "mobile"],
        },
      ),
    [runSmokeAction, runtimeMode],
  );

  return (
    <div className="app-shell" data-focus-visible="css">
      <header className="terminal-header">
        <div className="brand-lockup" aria-label="Bybit Trader 운영 대시보드">
          <div className="brand-mark" aria-hidden="true">
            BT
          </div>
          <div className="brand-text">
            <p className="brand-name">Bybit Trader</p>
            <p className="brand-sub">선물 매매봇 운영</p>
          </div>
        </div>

        <div className="header-summary" aria-label="핵심 상태">
          <div className="header-chip">
            <span>봇</span>
            <strong>{formatBotMode(bot?.mode)}</strong>
          </div>
          <div className="header-chip price-chip">
            <span>{market?.symbol || symbol}</span>
            <strong className={numberTone(market?.price24hPcnt)}>{market ? `${formatMoney(market.lastPrice)} ${coin}` : "조회 전"}</strong>
          </div>
        </div>

        <div className="header-actions">
          <Button
            icon={PauseCircle}
            variant="secondary"
            className="desktop-action"
            onClick={() => runAction("전체 일시정지", "/control/pause-all", { reason: "상단 버튼에서 전체 일시정지" })}
            disabled={isLoading}
          >
            봇 정지
          </Button>
          <Button
            icon={theme === "dark" ? Sun : Moon}
            variant="secondary"
            className="desktop-action"
            onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          >
            {theme === "dark" ? "라이트 모드" : "다크 모드"}
          </Button>
          <Button
            icon={RefreshCw}
            variant="secondary"
            className="compact-mobile"
            onClick={refreshActiveView}
            disabled={isLoading}
            aria-label="새로고침"
          >
            새로고침
          </Button>
          <Button
            icon={AlertTriangle}
            variant="secondary"
            className="danger-action compact-mobile"
            onClick={() => runAction("긴급 정지", "/control/emergency-stop", { reason: "상단 버튼에서 긴급 정지" })}
            disabled={isLoading}
            aria-label="긴급 정지"
          >
            긴급 정지
          </Button>
        </div>
      </header>

      <main className="workspace">
        <LiveMessage problemMessage={problemMessage} notice={notice} isLoading={isLoading} />

        <section className="status-strip" aria-label="운영 상태">
          <StatePill label="실행 모드" value={formatRuntime(runtimeMode)} />
          <StatePill label="연결 상태" value={formatExecution(summary.executionAvailable, hasLoaded)} tone={summary.executionAvailable ? "success" : "neutral"} />
          <StatePill label="동기화 상태" value={formatSyncStatus(primarySync?.lastSyncStatus)} tone={syncTone(primarySync?.lastSyncStatus)} />
          <StatePill label="마지막 갱신" value={formatDateTime(lastCapturedAt)} />
        </section>

        <nav className="view-tabs" aria-label="대시보드 보기" role="tablist">
          {VIEW_ITEMS.map((item) => {
            const Icon = item.icon;
            const active = activeView === item.id;
            return (
              <button
                key={item.id}
                type="button"
                className={`view-tab ${active ? "active" : ""}`}
                onClick={() => setActiveView(item.id)}
                id={`dashboard-tab-${item.id}`}
                role="tab"
                aria-selected={active}
                aria-controls={`dashboard-view-${item.id}`}
              >
                <Icon size={16} aria-hidden="true" />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>

        {activeView === "overview" ? (
          <section
            className="view-grid overview-grid"
            id="dashboard-view-overview"
            role="tabpanel"
            aria-labelledby="dashboard-tab-overview"
            data-view="overview"
          >
            <section className="panel account-board">
              <PanelHeader title="계정 스냅샷" description="초기 조회와 직접 새로고침 시 계정 수치를 확인해요." />
              <div className="hero-metrics">
                <SummaryMetric label="총 자산" value={account ? formatMoney(account.totalEquity) : "조회 전"} unit={coin} />
                <SummaryMetric
                  label="미실현 손익"
                  value={account ? formatMoney(account.totalPerpUnrealizedPnl) : "조회 전"}
                  unit={coin}
                  tone={numberTone(account?.totalPerpUnrealizedPnl)}
                />
                <SummaryMetric label="사용 가능 잔고" value={account ? formatMoney(account.totalAvailableBalance) : "조회 전"} unit={coin} />
                <SummaryMetric label="지갑 잔고" value={account ? formatMoney(account.totalWalletBalance) : "조회 전"} unit={coin} />
              </div>
              <dl className="detail-list">
                <StateRow label="봇 상태" value={formatBotMode(bot?.mode)} />
                <StateRow label="실행 모드" value={formatRuntime(runtimeMode)} />
                <StateRow label="하트비트" value={formatDateTime(bot?.heartbeatAt)} />
                <StateRow label="마지막 업데이트" value={formatDateTime(bot?.updatedAt)} />
                <StateRow label="계정 조회 시각" value={formatDateTime(account?.capturedAt)} />
              </dl>
            </section>

            <section className="panel">
              <PanelHeader title="실거래 성과" description="승률보다 순손익, 수수료, 종료 거래 수를 먼저 봐요." />
              <div className="stats-grid">
                <StatBlock label="순손익" value={formatMoney(primaryPerformance?.netPnl)} tone={numberTone(primaryPerformance?.netPnl)} />
                <StatBlock label="수수료" value={formatMoney(primaryPerformance?.fees)} />
                <StatBlock label="종료 거래" value={formatCount(primaryPerformance?.tradeCount)} />
                <StatBlock label="승률" value={formatPercentValue(primaryPerformance?.winRatePct)} />
              </div>
              <dl className="detail-list compact">
                <StateRow label="프로핏 팩터" value={formatRatioValue(primaryPerformance?.profitFactor)} />
                <StateRow label="기대값" value={formatMoney(primaryPerformance?.expectancy)} />
                <StateRow label="최대 종료 손실폭" value={formatPercentValue(primaryPerformance?.maxClosedTradeDrawdownPct)} />
                <StateRow label="마지막 종료" value={formatDateTime(primaryPerformance?.lastClosedAt)} />
              </dl>
            </section>

            <section className="panel">
              <PanelHeader title="마켓 동기화" description="닫힌 캔들 기준으로 어디까지 동기화됐는지 확인해요." />
              <div className="stats-grid">
                <StatBlock label="기준 타임프레임" value={primarySync?.timeframe || "M5"} />
                <StatBlock label="동기화 상태" value={formatSyncStatus(primarySync?.lastSyncStatus)} tone={syncTone(primarySync?.lastSyncStatus)} />
                <StatBlock label="최근 닫힌 캔들" value={formatShortDateTime(primarySync?.latestClosedOpenedAt)} />
                <StatBlock label="재시도 누적" value={formatCount(primarySync?.consecutiveRateLimitCount)} />
              </div>
              <dl className="detail-list compact">
                <StateRow label="마지막 동기화" value={formatDateTime(primarySync?.lastSyncAt)} />
                <StateRow label="최근 종료 알림" value={formatDateTime(mobileSummary.alerts?.latestExitAlertAt)} />
              </dl>
            </section>

            <section className="panel">
              <PanelHeader title="시장 흐름 수집" description="호가와 강제청산 데이터를 저장해 이후 전략 검증에 써요." />
              <div className="stats-grid">
                <StatBlock
                  label="수집 상태"
                  value={formatForwardMarketCaptureStatus(forwardMarketCapture)}
                  tone={forwardMarketCaptureTone(forwardMarketCapture)}
                />
                <StatBlock label="호가 1분 집계" value={formatShortDateTime(forwardMarketCapture.latestOrderBookBarAt)} />
                <StatBlock label="강제청산 집계" value={formatShortDateTime(forwardMarketCapture.latestLiquidationBarAt)} />
                <StatBlock label="수집 기준" value={forwardMarketCapture.enabled ? "최근 3분" : "수집 꺼짐"} />
              </div>
              <dl className="detail-list compact">
                <StateRow label="최근 호가 수집" value={formatDateTime(forwardMarketCapture.latestOrderBookBarAt)} />
                <StateRow label="최근 강제청산 수집" value={formatDateTime(forwardMarketCapture.latestLiquidationBarAt)} />
              </dl>
            </section>

            <section className="panel">
              <PanelHeader title="전략 요약" description="최근 전략 판단과 현재 적용 프로필을 함께 봐요." />
              <dl className="detail-list compact">
                <StateRow label="현재 선택 프로필" value={formatStrategyProfile(activeProfile)} />
                <StateRow label="운영 프로필" value={formatStrategyProfile(runtimeProfile)} />
                <StateRow label="최근 신호" value={latestSignal ? `${latestSignal.symbol} · ${formatSide(latestSignal.side)}` : "조회 전"} />
                <StateRow label="신호 시각" value={formatDateTime(latestSignal?.createdAt)} />
              </dl>
              {latestSignal ? (
                <div className="note-line">
                  <StatusBadge label={latestSignal.accepted ? "진입 허용" : latestSignal.rejectionReason || "진입 보류"} tone={latestSignal.accepted ? "success" : "neutral"} />
                  <p>{latestSignal.reasonCodes?.slice(0, 3).join(", ") || "사유 없음"}</p>
                </div>
              ) : (
                <EmptyInline
                  title={emptyTitle(dataState)}
                  message={emptyMessage(dataState, "최근 전략 신호가 없어요.", "새로고침하면 최근 판단이 여기에 표시돼요.")}
                />
              )}
            </section>
          </section>
        ) : false}

        {activeView === "trading" ? (
          <section
            className="view-grid trading-grid"
            id="dashboard-view-trading"
            role="tabpanel"
            aria-labelledby="dashboard-tab-trading"
            data-view="trading"
          >
            <section className="panel">
              <PanelHeader title="봇 제어" description="실시간 운영 명령은 여기에서 처리해요." />
              <div className="action-grid">
                <Button
                  icon={PlayCircle}
                  onClick={() =>
                    runAction("봇 시작", "/control/resume", { reason: "매매 탭에서 봇 시작" }, ["mobile", "summary"])
                  }
                  disabled={isLoading}
                >
                  봇 시작
                </Button>
                <Button
                  icon={RotateCcw}
                  variant="secondary"
                  onClick={() => runAction("계정 동기화", "/execution/reconcile", { symbol }, ["summary", "mobile"])}
                  disabled={isLoading}
                >
                  동기화
                </Button>
                <Button
                  icon={PauseCircle}
                  variant="secondary"
                  onClick={() => runAction("신규 진입 중단", "/control/pause-new-entries", { reason: "매매 탭에서 신규 진입 중단" })}
                  disabled={isLoading}
                >
                  신규 진입 중단
                </Button>
                <Button
                  icon={Send}
                  variant="secondary"
                  onClick={() => {
                    if (
                      !window.confirm("전략을 평가한 뒤 조건이 맞으면 실제 주문이 제출될 수 있어요. 계속할까요?")
                    ) {
                      return;
                    }
                    runAction(
                      "평가 후 주문",
                      "/execution/evaluate-and-submit",
                      {
                        symbol,
                        timeframe: "M5",
                        candleLimit: 18000,
                      },
                      ["summary", "mobile"],
                    );
                  }}
                  disabled={isLoading || !strategyEvaluationEnabled}
                >
                  평가 후 주문
                </Button>
              </div>
              <dl className="detail-list compact">
                <StateRow label="계정 연결" value={formatExecution(summary.executionAvailable, hasLoaded)} />
                <StateRow label="최근 변경" value={formatDateTime(bot?.updatedAt)} />
              </dl>
            </section>

            <section className="panel">
              <PanelHeader title="전략 프로필" description="운영 프로필을 바꾸고 현재 적용 상태를 확인해요." />
              <dl className="detail-list compact">
                <StateRow label="현재 선택" value={formatStrategyProfile(activeProfile)} />
                <StateRow label="운영 루프" value={formatStrategyProfile(runtimeProfile)} />
                <StateRow label="적용 범위" value={activeProfile?.kindLabel || "조회 전"} />
                <StateRow label="변경 시각" value={formatDateTime(strategyState.updatedAt)} />
              </dl>
              <div className="profile-options">
                {strategyProfiles.map((profile) => {
                  const active = profile.id === strategyState.activeProfileId;
                  return (
                    <Button
                      key={profile.id}
                      icon={SlidersHorizontal}
                      variant={active ? "primary" : "secondary"}
                      onClick={() => activateStrategyProfile(profile)}
                      disabled={isLoading || active}
                    >
                      {active ? `${profile.name} 적용 중` : `${profile.name} 적용`}
                    </Button>
                  );
                })}
              </div>
              {activeProfile?.riskNote ? <p className="helper-text">{activeProfile.riskNote}</p> : false}
            </section>

            <section className="panel">
              <PanelHeader title="수동 주문" description="운영 모드 확인 후 수동 주문과 포지션 정리를 실행해요." />
              <div className="inline-fields">
                <Field label="주문 방향">
                  <select aria-label="주문 방향" value={smokeSide} onChange={(event) => setSmokeSide(event.target.value)}>
                    <option value="BUY">롱</option>
                    <option value="SELL">숏</option>
                  </select>
                </Field>
                <Field label="주문 수량">
                  <input aria-label="주문 수량" value={smokeQuantity} inputMode="decimal" onChange={(event) => setSmokeQuantity(event.target.value)} />
                </Field>
              </div>
              <Button icon={Zap} onClick={runManualMarketOrder} disabled={isLoading || !manualExchangeEnabled}>
                수동 시장가 주문
              </Button>
            </section>

            <section className="panel full-width">
              <PanelHeader title="포지션" description="현재 열린 포지션과 미실현 손익이에요." />
              <DataTable
                columns={["심볼", "방향", "수량", "진입가", "현재가", "미실현 손익", "작업"]}
                isLoading={isLoading}
                emptyTitle={emptyTitle(dataState)}
                emptyMessage={emptyMessage(dataState, "열린 포지션이 없어요.", "새로고침하면 열린 포지션이 여기에 표시돼요.")}
              >
                {positions.map((position, index) => (
                  <tr key={`${position.symbol}-${position.side}-${index}`}>
                    <td data-label="심볼">{position.symbol}</td>
                    <td data-label="방향">
                      <SideText side={position.side} />
                    </td>
                    <td data-label="수량">{formatNumber(position.size)}</td>
                    <td data-label="진입가">{formatMoney(position.entryPrice)}</td>
                    <td data-label="현재가">{formatMoney(position.markPrice)}</td>
                    <td data-label="미실현 손익" className={numberClass(position.unrealizedPnl)}>
                      {formatMoney(position.unrealizedPnl)}
                    </td>
                    <td data-label="작업" className="table-action-cell">
                      <Button
                        icon={CircleStop}
                        variant="secondary"
                        className="table-action danger-action"
                        onClick={() => runClosePosition(position)}
                        disabled={isLoading || !manualExchangeEnabled || !isPositiveNumber(position.size)}
                      >
                        청산
                      </Button>
                    </td>
                  </tr>
                ))}
              </DataTable>
            </section>

            <section className="panel full-width">
              <PanelHeader title="미체결 주문" description="거래소에 남아 있는 주문이에요." />
              <DataTable
                columns={["주문 ID", "방향", "유형", "상태", "수량", "생성 시각", "작업"]}
                isLoading={isLoading}
                emptyTitle={emptyTitle(dataState)}
                emptyMessage={emptyMessage(dataState, "미체결 주문이 없어요.", "새로고침하면 미체결 주문이 여기에 표시돼요.")}
              >
                {openOrders.map((order, index) => (
                  <tr key={`${order.exchangeOrderId || order.clientOrderId}-${index}`}>
                    <td data-label="주문 ID">{shortId(order.exchangeOrderId || order.clientOrderId)}</td>
                    <td data-label="방향">
                      <SideText side={order.side} />
                    </td>
                    <td data-label="유형">{formatOrderType(order.orderType)}</td>
                    <td data-label="상태">{formatOrderStatus(order.status)}</td>
                    <td data-label="수량">{formatNumber(order.quantity)}</td>
                    <td data-label="생성 시각">{formatDateTime(order.createdAt)}</td>
                    <td data-label="작업" className="table-action-cell">
                      <Button
                        icon={CircleStop}
                        variant="secondary"
                        className="table-action danger-action"
                        onClick={() => runCancelOrder(order)}
                        disabled={isLoading || !summary.executionAvailable}
                      >
                        취소
                      </Button>
                    </td>
                  </tr>
                ))}
              </DataTable>
            </section>
          </section>
        ) : false}

        {activeView === "activity" ? (
          <section
            className="view-grid activity-grid"
            id="dashboard-view-activity"
            role="tabpanel"
            aria-labelledby="dashboard-tab-activity"
            data-view="activity"
          >
            <section className="panel full-width">
              <PanelHeader
                title="종료 거래"
                description="실거래 종료 이력과 순손익, 수수료를 시간순으로 확인해요."
                action={closedTradeCursor ? <StatusBadge label={`다음 커서 ${closedTradeCursor}`} tone="neutral" /> : false}
              />
              <DataTable
                columns={["종료 시각", "방향", "진입가", "청산가", "순손익", "수수료", "사유"]}
                isLoading={isLoading}
                emptyTitle={emptyTitle(dataState)}
                emptyMessage={emptyMessage(dataState, "종료 거래가 아직 없어요.", "종료 거래가 생기면 여기에 표시돼요.")}
              >
                {activityClosedTrades.map((trade, index) => (
                  <tr key={`${trade.tradeId || trade.exchangeOrderId || index}`}>
                    <td data-label="종료 시각">{formatDateTime(trade.closedAt)}</td>
                    <td data-label="방향">
                      <SideText side={trade.side} />
                    </td>
                    <td data-label="진입가">{formatMoney(trade.entryPrice)}</td>
                    <td data-label="청산가">{formatMoney(trade.exitPrice)}</td>
                    <td data-label="순손익" className={numberClass(trade.netPnl)}>
                      {formatMoney(trade.netPnl)}
                    </td>
                    <td data-label="수수료">{formatMoney(trade.fees)}</td>
                    <td data-label="사유">{formatExitReason(trade.exitReason)}</td>
                  </tr>
                ))}
              </DataTable>
            </section>

            <section className="panel">
              <PanelHeader title="최근 체결" description="거래소 체결 내역을 시간순으로 확인해요." />
              <DataTable
                columns={["시각", "방향", "가격", "수량", "수수료"]}
                isLoading={isLoading}
                emptyTitle={emptyTitle(dataState)}
                emptyMessage={emptyMessage(dataState, "최근 체결 내역이 없어요.", "새로고침하면 최근 체결 내역이 여기에 표시돼요.")}
              >
                {executions.map((fill, index) => (
                  <tr key={`${fill.exchangeOrderId}-${fill.executedAt}-${index}`}>
                    <td data-label="시각">{formatDateTime(fill.executedAt)}</td>
                    <td data-label="방향">
                      <SideText side={fill.side} />
                    </td>
                    <td data-label="가격">{formatMoney(fill.price)}</td>
                    <td data-label="수량">{formatNumber(fill.quantity)}</td>
                    <td data-label="수수료">{formatMoney(fill.fee)}</td>
                  </tr>
                ))}
              </DataTable>
            </section>

            <section className="panel">
              <PanelHeader title="전략 신호" description="전략이 남긴 신호와 거절 사유예요." />
              <SignalList signals={activitySignals} isLoading={isLoading} dataState={dataState} />
            </section>
          </section>
        ) : false}

        {activeView === "settings" ? (
          <section
            className="view-grid settings-grid"
            id="dashboard-view-settings"
            role="tabpanel"
            aria-labelledby="dashboard-tab-settings"
            data-view="settings"
          >
            <section className="panel">
              <PanelHeader title={CONNECTION_SETTINGS_TITLE} description="값을 바꾼 뒤 적용하면 새 연결로 진단을 조회해요." />
              <div className="settings-form">
                <Field label="API 기준 주소">
                  <input aria-label="API 기준 주소" value={apiBaseDraft} onChange={(event) => setApiBaseDraft(event.target.value)} />
                </Field>
                <Field label="운영 접근키">
                  <input
                    aria-label="운영 접근키"
                    type="password"
                    value={accessKeyDraft}
                    onChange={(event) => setAccessKeyDraft(event.target.value)}
                  />
                </Field>
                <Field label="심볼">
                  <input
                    aria-label="심볼"
                    value={symbolDraft}
                    onChange={(event) => setSymbolDraft(event.target.value.toUpperCase())}
                  />
                </Field>
                <Field label="기준 코인">
                  <input
                    aria-label="기준 코인"
                    value={coinDraft}
                    onChange={(event) => setCoinDraft(event.target.value.toUpperCase())}
                  />
                </Field>
              </div>
              <div className="action-grid">
                <Button icon={RefreshCw} onClick={refreshActiveView} disabled={isLoading}>
                  {connectionSettingsChanged ? "설정 적용 및 조회" : "지금 다시 조회"}
                </Button>
                <Button
                  icon={theme === "dark" ? Sun : Moon}
                  variant="secondary"
                  onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
                >
                  {theme === "dark" ? "라이트 모드" : "다크 모드"}
                </Button>
              </div>
            </section>

            <section className="panel">
              <PanelHeader title="동기화 진단" description="닫힌 캔들 동기화와 연결 상태를 한 번 더 점검해요." />
              {syncStatus.length > 0 ? (
                <div className="checkpoint-list">
                  {syncStatus.map((checkpoint) => (
                    <div key={`${checkpoint.timeframe}-${checkpoint.latestClosedOpenedAt || checkpoint.lastSyncAt}`} className="checkpoint-row">
                      <div>
                        <strong>{checkpoint.timeframe}</strong>
                        <span>{formatShortDateTime(checkpoint.latestClosedOpenedAt)}</span>
                      </div>
                      <div>
                        <StatusBadge label={formatSyncStatus(checkpoint.lastSyncStatus)} tone={syncTone(checkpoint.lastSyncStatus)} />
                        <span>{formatDateTime(checkpoint.lastSyncAt)}</span>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <EmptyInline
                  title={emptyTitle(dataState)}
                  message={emptyMessage(dataState, "동기화 체크포인트가 없어요.", "닫힌 캔들 동기화가 시작되면 여기에 표시돼요.")}
                />
              )}
            </section>

            <section className="panel">
              <PanelHeader title="기능 테스트" description="알림, 조회, 제어 경로를 운영 전에 점검해요." />
              <div className="action-grid">
                <Button
                  icon={CheckCircle2}
                  variant="secondary"
                  onClick={() =>
                    runSmokeAction("Discord 웹훅", "/ops/smoke/discord", {
                      message: `Bybit Trader 테스트넷 알림 테스트예요. 대상 심볼: ${symbol}`,
                    })
                  }
                  disabled={isLoading}
                >
                  Discord 전송
                </Button>
                <Button
                  icon={RefreshCw}
                  variant="secondary"
                  onClick={() => runSmokeAction("거래소 조회", "/ops/smoke/exchange-read", { symbol, coin })}
                  disabled={isLoading}
                >
                  조회 테스트
                </Button>
                <Button
                  icon={PauseCircle}
                  variant="secondary"
                  onClick={() =>
                    runSmokeAction(
                      "정지 테스트",
                      "/ops/smoke/control-pause",
                      {
                        reason: "대시보드 TESTNET 정지 테스트",
                      },
                      { refreshScopes: ["mobile"] },
                    )
                  }
                  disabled={isLoading}
                >
                  정지 테스트
                </Button>
                <Button
                  icon={PlayCircle}
                  variant="secondary"
                  onClick={() =>
                    runSmokeAction(
                      "재가동 테스트",
                      "/ops/smoke/control-resume",
                      {
                        reason: "대시보드 TESTNET 재가동 테스트",
                      },
                      { refreshScopes: ["mobile"] },
                    )
                  }
                  disabled={isLoading}
                >
                  재가동 테스트
                </Button>
              </div>
              <SmokeResult result={smokeResult} />
            </section>
          </section>
        ) : false}
      </main>
    </div>
  );
}

function Button({ children, icon: Icon, variant = "primary", className = "", ...props }) {
  return (
    <button className={`button ${variant} ${className}`.trim()} type="button" {...props}>
      {Icon ? <Icon size={17} aria-hidden="true" /> : false}
      <span>{children}</span>
    </button>
  );
}

function Field({ label, children }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}

function PanelHeader({ title, description, action }) {
  return (
    <div className="panel-header">
      <div>
        <h2>{title}</h2>
        <p>{description}</p>
      </div>
      {action ? <div className="panel-action">{action}</div> : false}
    </div>
  );
}

function SummaryMetric({ label, value, unit, tone = "neutral" }) {
  return (
    <div className="summary-metric">
      <span>{label}</span>
      <strong className={tone}>{value}</strong>
      {unit ? <em>{unit}</em> : false}
    </div>
  );
}

function StatBlock({ label, value, tone = "neutral" }) {
  return (
    <div className="stat-block">
      <span>{label}</span>
      <strong className={tone}>{value || "조회 전"}</strong>
    </div>
  );
}

function StatePill({ label, value, tone = "neutral" }) {
  return (
    <div className={`state-pill ${tone}`}>
      <span>{label}</span>
      <strong>{value || "확인 필요"}</strong>
    </div>
  );
}

function LiveMessage({ problemMessage, notice, isLoading }) {
  if (problemMessage) {
    return (
      <div className="message danger-message" role="alert">
        <AlertTriangle size={17} aria-hidden="true" />
        {problemMessage}
      </div>
    );
  }
  if (isLoading) {
    return (
      <div className="message" aria-live="polite">
        <RefreshCw className="spin" size={17} aria-hidden="true" />
        데이터를 불러오고 있어요.
      </div>
    );
  }
  if (notice) {
    return (
      <div className="message success" aria-live="polite">
        <CheckCircle2 size={17} aria-hidden="true" />
        {notice}
      </div>
    );
  }
  return false;
}

function DataTable({ columns, children, isLoading, emptyTitle: title, emptyMessage: message }) {
  const rows = Array.isArray(children) ? children.filter(Boolean) : children ? [children] : [];
  if (isLoading && rows.length === 0) {
    return <SkeletonRows columns={columns.length} />;
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column}>{column}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length > 0 ? (
            rows
          ) : (
            <tr>
              <td colSpan={columns.length}>
                <EmptyInline title={title} message={message} />
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function SkeletonRows({ columns }) {
  return (
    <div className="skeleton-table" aria-label="불러오는 중">
      {Array.from({ length: 4 }).map((_, rowIndex) => (
        <div className="skeleton-row" key={rowIndex} style={{ "--columns": columns }}>
          {Array.from({ length: columns }).map((__, columnIndex) => (
            <span key={columnIndex} />
          ))}
        </div>
      ))}
    </div>
  );
}

function EmptyInline({ title = "표시할 데이터가 없어요", message }) {
  return (
    <div className="empty-inline">
      <CircleStop size={17} aria-hidden="true" />
      <div>
        <strong>{title}</strong>
        <span>{message}</span>
      </div>
    </div>
  );
}

function SignalList({ signals, isLoading, dataState }) {
  if (isLoading && signals.length === 0) {
    return <SkeletonRows columns={3} />;
  }
  if (signals.length === 0) {
    return (
      <EmptyInline
        title={emptyTitle(dataState)}
        message={emptyMessage(dataState, "최근 신호가 없어요.", "새로고침하면 전략 신호와 거절 사유가 여기에 표시돼요.")}
      />
    );
  }
  return (
    <ul className="signal-list">
      {signals.map((signal) => (
        <li key={signal.id || `${signal.symbol}-${signal.createdAt}`}>
          <div>
            <strong>
              {signal.symbol} · <SideText side={signal.side} />
            </strong>
            <span>{formatDateTime(signal.createdAt)}</span>
          </div>
          <p>{signal.reasonCodes?.slice(0, 3).join(", ") || "사유 없음"}</p>
          <StatusBadge label={signal.accepted ? "진입 허용" : signal.rejectionReason || "진입 보류"} tone={signal.accepted ? "success" : "neutral"} />
        </li>
      ))}
    </ul>
  );
}

function SmokeResult({ result }) {
  if (!result) {
    return (
      <div className="smoke-result idle">
        <strong>테스트 결과</strong>
        <span>아직 실행한 기능 테스트가 없어요.</span>
      </div>
    );
  }
  const summary = buildSmokeSummary(result.responseBody);
  return (
    <div className={`smoke-result ${result.status === "PASS" ? "success" : result.status === SMOKE_ATTENTION_STATUS ? "danger" : "idle"}`}>
      <div>
        <strong>{result.label}</strong>
        <StatusBadge label={formatSmokeStatus(result.status)} tone={result.status === "PASS" ? "success" : result.status === SMOKE_ATTENTION_STATUS ? "danger" : "neutral"} />
      </div>
      {summary ? <SmokeSummary summary={summary} /> : false}
      {result.responseBody ? (
        <details className="smoke-raw">
          <summary>응답 원문 보기</summary>
          <pre>{JSON.stringify(result.responseBody, jsonReplacer, 2)}</pre>
        </details>
      ) : (
        <span>서버 응답을 기다리고 있어요.</span>
      )}
    </div>
  );
}

function SmokeSummary({ summary }) {
  return (
    <div className="smoke-summary">
      <strong>{summary.title}</strong>
      <dl>
        {summary.items.map((item) => (
          <div key={item.label}>
            <dt>{item.label}</dt>
            <dd className={item.tone || "neutral"}>{item.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function StateRow({ label, value }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value || "확인 필요"}</dd>
    </div>
  );
}

function StatusBadge({ label, tone = "neutral" }) {
  return <span className={`status-badge ${tone}`}>{label}</span>;
}

function SideText({ side }) {
  const text = side === "BUY" ? "롱" : side === "SELL" ? "숏" : side || "확인 필요";
  return <span className={side === "BUY" ? "side buy" : side === "SELL" ? "side sell" : "side"}>{text}</span>;
}

function activationScopes(activeView) {
  if (activeView === "trading") return ["summary"];
  if (activeView === "activity") return ["mobile", "closedTrades", "summary"];
  if (activeView === "settings") return ["sync"];
  return ["mobile"];
}

function manualRefreshScopes(activeView) {
  if (activeView === "trading") return ["summary", "mobile", "profiles"];
  if (activeView === "activity") return ["mobile", "closedTrades", "summary"];
  if (activeView === "settings") return ["sync"];
  return ["mobile", "performance", "summary", "profiles"];
}

function pollingScopes(activeView, lastFetchedAt) {
  if (activeView === "trading") return ["summary"];
  if (activeView === "activity") {
    return [
      "mobile",
      ...(scopeIsDue(lastFetchedAt, "closedTrades", CLOSED_TRADES_REFRESH_MS) ? ["closedTrades"] : []),
      ...(scopeIsDue(lastFetchedAt, "summary", ACTIVITY_SUMMARY_REFRESH_MS) ? ["summary"] : []),
    ];
  }
  if (activeView === "settings") {
    return scopeIsDue(lastFetchedAt, "sync", SYNC_STATUS_REFRESH_MS) ? ["sync"] : [];
  }
  return [
    "mobile",
    ...(scopeIsDue(lastFetchedAt, "summary", OVERVIEW_SUMMARY_REFRESH_MS) ? ["summary"] : []),
    ...(scopeIsDue(lastFetchedAt, "performance", PERFORMANCE_REFRESH_MS) ? ["performance"] : []),
  ];
}

function scopeIsDue(lastFetchedAt, scope, intervalMs) {
  const lastFetched = lastFetchedAt[scope];
  return !lastFetched || Date.now() - lastFetched >= intervalMs;
}

function viewLabel(activeView) {
  return VIEW_ITEMS.find((item) => item.id === activeView)?.label || "현재 화면";
}

function mostRecentBot(summaryBot, mobileBot) {
  if (!mobileBot?.updatedAt) return summaryBot || EMPTY_SUMMARY.bot;
  if (!summaryBot?.updatedAt) return mobileBot;
  return new Date(mobileBot.updatedAt).getTime() >= new Date(summaryBot.updatedAt).getTime() ? mobileBot : summaryBot;
}

function resolveInitialTheme() {
  const storedTheme = localStorage.getItem(STORAGE_KEYS.theme);
  if (storedTheme === "light" || storedTheme === "dark") return storedTheme;
  if (window.matchMedia?.("(prefers-color-scheme: dark)").matches) return "dark";
  return "light";
}

function parseJsonBody(responseText) {
  if (!responseText) return {};
  try {
    return JSON.parse(responseText);
  } catch {
    return { message: responseText };
  }
}

function resolveFailureMessage(responseBody) {
  return responseBody?.message || responseBody?.detail || "요청을 마치지 못했어요. 서버 로그와 연결 상태를 확인한 뒤 다시 시도해 주세요.";
}

function jsonReplacer(_key, value) {
  return value;
}

function buildSmokeSummary(responseBody) {
  if (!responseBody) return false;
  if (responseBody.pauseMode || responseBody.resumeMode) {
    const pauseConfirmed = responseBody.pauseMode === "PAUSE_ALL";
    const resumeConfirmed = responseBody.resumeMode === "RUNNING";
    const resumeRequested = resumeConfirmed || responseBody.resumeMode === "RESUME_PENDING_CHECK";
    return {
      title: pauseConfirmed && resumeRequested ? "정지 후 재가동 확인 요청이 접수됐어요." : "상태 전환 결과를 확인해 주세요.",
      items: [
        {
          label: "1단계 정지",
          value: pauseConfirmed ? `확인됨 · ${formatBotMode(responseBody.pauseMode)}` : formatBotMode(responseBody.pauseMode),
          tone: pauseConfirmed ? "positive" : "neutral",
        },
        {
          label: "2단계 재가동",
          value: resumeConfirmed
            ? `확인 완료 · ${formatBotMode(responseBody.resumeMode)}`
            : resumeRequested
              ? `확인 중 · ${formatBotMode(responseBody.resumeMode)}`
              : formatBotMode(responseBody.resumeMode),
          tone: resumeRequested ? "positive" : "neutral",
        },
      ],
    };
  }
  if (responseBody.action || responseBody.newMode) {
    const isPause = responseBody.action === "PAUSE_ALL";
    const isResume = responseBody.action === "RESUME";
    const isResumePending = isResume && responseBody.newMode === "RESUME_PENDING_CHECK";
    return {
      title: isPause ? "정지 요청이 처리됐어요." : isResumePending ? "재가동 확인 요청이 접수됐어요." : isResume ? "재가동이 확인됐어요." : "상태 변경 결과예요.",
      items: [
        { label: "이전 상태", value: formatBotMode(responseBody.previousMode), tone: "neutral" },
        {
          label: "현재 상태",
          value: isResumePending ? `확인 중 · ${formatBotMode(responseBody.newMode)}` : formatBotMode(responseBody.newMode),
          tone: isPause || isResume ? "positive" : "neutral",
        },
      ],
    };
  }
  if (responseBody.code) {
    return {
      title: "요청이 처리되지 않았어요.",
      items: [
        { label: "오류 코드", value: `${responseBody.providerCode || responseBody.code} · 서버 로그 확인`, tone: "negative" },
        {
          label: "확인 항목",
          value: responseBody.providerMessage || responseBody.message || "서버 로그 확인",
          tone: "negative",
        },
      ],
    };
  }
  if (responseBody.eventId || responseBody.status) {
    return {
      title: "테스트 요청이 접수됐어요.",
      items: [
        { label: "상태", value: responseBody.status || "확인 필요", tone: "positive" },
        { label: "참조 ID", value: shortId(responseBody.eventId || responseBody.requestId || responseBody.orderId), tone: "neutral" },
      ],
    };
  }
  return false;
}

function numberTone(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return "neutral";
  if (parsed > 0) return "positive";
  if (parsed < 0) return "negative";
  return "neutral";
}

function numberClass(value) {
  const tone = numberTone(value);
  return tone === "neutral" ? "" : `numeric ${tone}`;
}

function formatMoney(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return "조회 전";
  return new Intl.NumberFormat("ko-KR", {
    minimumFractionDigits: Math.abs(parsed) >= 100 ? 0 : 2,
    maximumFractionDigits: 3,
  }).format(parsed);
}

function formatNumber(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return "조회 전";
  return new Intl.NumberFormat("ko-KR", {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  }).format(parsed);
}

function formatPercentValue(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return "조회 전";
  return `${parsed.toFixed(2)}%`;
}

function formatRatioValue(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return "조회 전";
  return parsed.toFixed(2);
}

function formatCount(value) {
  if (value == void 0 || value === "") return "조회 전";
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return String(value);
  return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 }).format(parsed);
}

function formatDateTime(value) {
  if (!value) return "조회 전";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "조회 전";
  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatShortDateTime(value) {
  if (!value) return "조회 전";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "조회 전";
  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatExecution(value, hasLoaded) {
  if (!hasLoaded) return "조회 전";
  return value ? "연결됨" : "확인 필요";
}

function formatForwardMarketCaptureStatus(capture) {
  if (!capture?.enabled) return "수집 꺼짐";
  if (capture.orderBookFresh) return "수집 확인됨";
  if (!capture.latestOrderBookBarAt) return "첫 집계 대기";
  return "수집 확인 필요";
}

function forwardMarketCaptureTone(capture) {
  if (!capture?.enabled) return "neutral";
  if (capture.orderBookFresh) return "positive";
  return "neutral";
}

function formatBotMode(mode) {
  if (!mode) return "확인 필요";
  const labels = {
    RUNNING: "운영 중",
    PAUSE_ALL: "전체 정지",
    PAUSE_NEW_ENTRIES: "신규 진입 중단",
    RESUME_PENDING_CHECK: "재가동 확인 중",
  };
  return labels[mode] || mode;
}

function formatRuntime(mode) {
  if (!mode) return "확인 필요";
  const labels = {
    LIVE: "실거래",
    TESTNET: "테스트넷",
    PAPER: "모의",
    MANUAL: "수동",
  };
  return labels[mode] || mode;
}

function formatSide(side) {
  return side === "BUY" ? "롱" : side === "SELL" ? "숏" : side || "확인 필요";
}

function formatOrderType(value) {
  const labels = {
    MARKET: "시장가",
    LIMIT: "지정가",
  };
  return labels[value] || value || "확인 필요";
}

function formatOrderStatus(value) {
  const labels = {
    NEW: "대기",
    PARTIALLY_FILLED: "부분 체결",
    FILLED: "체결 완료",
    CANCELED: "취소",
    REJECTED: "거절",
  };
  return labels[value] || value || "확인 필요";
}

function formatSyncStatus(value) {
  if (!value) return "조회 전";
  const labels = {
    SUCCESS: "정상",
    RATE_LIMITED: "제한 감지",
    [SYNC_STOPPED_STATUS]: "동기화 중단",
  };
  return labels[value] || value;
}

function syncTone(value) {
  if (value === "SUCCESS") return "success";
  if (value === SYNC_STOPPED_STATUS) return "danger";
  if (value === "RATE_LIMITED") return "warning";
  return "neutral";
}

function formatExitReason(value) {
  if (!value) return "확인 필요";
  const labels = {
    TAKE_PROFIT: "익절",
    STOP_LOSS: "손절",
    MANUAL_CLOSE: "수동 청산",
    SIGNAL_EXIT: "전략 종료",
  };
  return labels[value] || value;
}

function formatStrategyProfile(profile) {
  if (!profile) return "조회 전";
  return profile.name || profile.id || "조회 전";
}

function formatSmokeStatus(status) {
  const labels = {
    PASS: "성공",
    [SMOKE_ATTENTION_STATUS]: "다시 확인",
    RUNNING: "진행 중",
  };
  return labels[status] || "확인 필요";
}

function shortId(value) {
  if (!value) return "확인 필요";
  const text = String(value);
  if (text.length <= 10) return text;
  return `${text.slice(0, 4)}…${text.slice(-4)}`;
}

function isManualRuntime(runtimeMode) {
  return runtimeMode === "LIVE" || runtimeMode === "TESTNET";
}

function isPositiveNumber(value) {
  return Number(value) > 0;
}

function manualAcknowledgement(runtimeMode, action) {
  return `${runtimeMode || "UNKNOWN"}:${action}:CONFIRMED`;
}

function emptyTitle(dataState) {
  if (dataState === "idle") return "아직 조회하지 않았어요";
  if (dataState === ATTENTION_DATA_STATE) return "불러오지 못했어요";
  return "표시할 데이터가 없어요";
}

function emptyMessage(dataState, readyMessage, idleMessage) {
  if (dataState === "idle") return idleMessage;
  if (dataState === ATTENTION_DATA_STATE) return "접근키와 API 주소를 확인한 뒤 다시 시도해 주세요.";
  return readyMessage;
}

createRoot(document.getElementById("root")).render(<App />);
