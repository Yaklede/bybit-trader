import { useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  AlertTriangle,
  CheckCircle2,
  CircleStop,
  EyeOff,
  Moon,
  PauseCircle,
  PlayCircle,
  RefreshCw,
  RotateCcw,
  Send,
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

const EMPTY_SUMMARY = {
  runtimeMode: "",
  executionAvailable: false,
  bot: { mode: "확인 필요", updatedAt: "", heartbeatAt: "" },
  recentSignals: [],
  recentTrades: [],
};

const EMPTY_STRATEGY_STATE = {
  activeProfileId: "",
  runtimeProfileId: "",
  profiles: [],
};

function App() {
  const [apiBase, setApiBase] = useState(() => localStorage.getItem(STORAGE_KEYS.apiBase) || "/api");
  const [accessKey, setAccessKey] = useState(() => localStorage.getItem(STORAGE_KEYS.accessKey) || "");
  const [theme, setTheme] = useState(resolveInitialTheme);
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [coin, setCoin] = useState("USDT");
  const [summary, setSummary] = useState(EMPTY_SUMMARY);
  const [strategyState, setStrategyState] = useState(EMPTY_STRATEGY_STATE);
  const [isLoading, setIsLoading] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);
  const [now, setNow] = useState(() => new Date());
  const [problemMessage, setProblemMessage] = useState("");
  const [notice, setNotice] = useState("");
  const [smokeResult, setSmokeResult] = useState();
  const [smokeQuantity, setSmokeQuantity] = useState("0.001");
  const [smokeSide, setSmokeSide] = useState("BUY");

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

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

  const loadSummary = useCallback(async () => {
    setIsLoading(true);
    setProblemMessage("");
    try {
      const [nextSummary, nextStrategyState] = await Promise.all([
        client.request(`/dashboard/summary?symbol=${encodeURIComponent(symbol)}&coin=${encodeURIComponent(coin)}&limit=20`),
        client.request("/strategy/profiles"),
      ]);
      setSummary(nextSummary);
      setStrategyState(nextStrategyState);
      setHasLoaded(true);
      setNotice("대시보드를 새로고침했어요.");
    } catch (nextProblem) {
      setProblemMessage(nextProblem.message || "데이터를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
    } finally {
      setIsLoading(false);
    }
  }, [client, coin, symbol]);

  const runAction = useCallback(
    async (label, path, body) => {
      setIsLoading(true);
      setProblemMessage("");
      setNotice("");
      try {
        const result = await client.request(path, {
          method: "POST",
          body: JSON.stringify(body),
        });
        setNotice(`${label} 요청을 보냈어요.`);
        await loadSummary();
        return result;
      } catch (nextProblem) {
        setProblemMessage(nextProblem.message || "요청을 마치지 못했어요. 접근키, API 주소, 서버 상태를 확인한 뒤 다시 시도해 주세요.");
        return false;
      } finally {
        setIsLoading(false);
      }
    },
    [client, loadSummary],
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
        await loadSummary();
        setNotice(`${label} 테스트가 완료됐어요.`);
        return result;
      } catch (nextProblem) {
        setSmokeResult({
          label,
          status: "FAIL",
          ...(nextProblem.responseBody ? { responseBody: nextProblem.responseBody } : {}),
        });
        setProblemMessage(nextProblem.message || `${label} 테스트를 마치지 못했어요.`);
        return false;
      } finally {
        setIsLoading(false);
      }
    },
    [client, loadSummary],
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
  const signals = summary.recentSignals || [];
  const dataState = problemMessage ? "error" : hasLoaded ? "ready" : "idle";
  const manualExchangeEnabled = isManualRuntime(summary.runtimeMode) && summary.executionAvailable;
  const activeProfile = strategyState.activeProfile;
  const runtimeProfile = strategyState.runtimeProfile;
  const strategyProfiles = strategyState.profiles || [];
  const strategyEvaluationEnabled = activeProfile?.runtimeEligible !== false;

  const runManualMarketOrder = useCallback(
    () =>
      runSmokeAction(
        "수동 시장가 주문",
        "/execution/manual/market-order",
        {
          symbol,
          side: smokeSide,
          quantity: smokeQuantity,
          acknowledgement: manualAcknowledgement(summary.runtimeMode, "MARKET_ORDER"),
        },
        {
          confirmMessage:
            `${formatRuntime(summary.runtimeMode)} 계정에 ${symbol} ${formatSide(smokeSide)} ` +
            `${smokeQuantity} 시장가 주문을 전송합니다. 계속할까요?`,
        },
      ),
    [runSmokeAction, smokeQuantity, smokeSide, summary.runtimeMode, symbol],
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
          acknowledgement: manualAcknowledgement(summary.runtimeMode, "CLOSE_POSITION"),
        },
        {
          confirmMessage:
            `${formatRuntime(summary.runtimeMode)} 계정에서 ${position.symbol} ${formatSide(position.side)} 포지션 ` +
            `${position.size}을 포지션 감소 전용 시장가로 청산합니다. 계속할까요?`,
        },
      ),
    [runSmokeAction, summary.runtimeMode],
  );

  return (
    <div className="app-shell" data-focus-visible="css">
      <header className="terminal-header">
        <div className="brand-lockup" aria-label="Bybit Trader 운영 대시보드">
          <div className="brand-mark" aria-hidden="true">
            BT
          </div>
          <div>
            <p className="brand-name">Bybit Trader</p>
            <p className="brand-sub">선물 매매봇 운영</p>
          </div>
        </div>

        <div className="market-strip" aria-label="시장 상태">
          <HeaderMetric label="마켓" value={market?.symbol || symbol} />
          <HeaderMetric label="현재가" value={market ? `${formatMoney(market.lastPrice)} USDT` : "조회 전"} />
          <HeaderMetric label="24H 변화율" value={formatRatioPercent(market?.price24hPcnt)} tone={numberTone(market?.price24hPcnt)} />
          <HeaderMetric label="펀딩 / 예정 시각" value={formatFundingSummary(market)} />
          <HeaderMetric label="서버 시간" value={formatFullDateTime(now)} />
        </div>

        <div className="header-actions">
          <Button
            icon={PauseCircle}
            variant="secondary"
            onClick={() => runAction("전체 일시정지", "/control/pause-all", { reason: "상단 버튼에서 전체 일시정지" })}
            disabled={isLoading}
          >
            봇 정지
          </Button>
          <Button
            icon={AlertTriangle}
            variant="secondary"
            onClick={() => runAction("긴급 정지", "/control/emergency-stop", { reason: "상단 버튼에서 긴급 정지" })}
            disabled={isLoading}
          >
            긴급 정지
          </Button>
          <Button icon={theme === "dark" ? Sun : Moon} variant="secondary" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>
            {theme === "dark" ? "라이트 모드" : "다크 모드"}
          </Button>
          <Button icon={RefreshCw} onClick={loadSummary} disabled={isLoading}>
            새로고침
          </Button>
        </div>
      </header>

      <main className="workspace">
        <section className="connection-bar" aria-label="API 연결값 입력">
          <div className="settings-heading">
            <h2>연결 설정</h2>
            <p>서버, 접근키, 시장 기준값을 지정해요.</p>
          </div>
          <Field label="API 기준 주소">
            <input aria-label="API 기준 주소" value={apiBase} onChange={(event) => setApiBase(event.target.value)} />
          </Field>
          <Field label="운영 접근키">
            <input
              aria-label="운영 접근키"
              type="password"
              value={accessKey}
              onChange={(event) => setAccessKey(event.target.value)}
            />
          </Field>
          <Field label="심볼">
            <input aria-label="심볼" value={symbol} onChange={(event) => setSymbol(event.target.value.toUpperCase())} />
          </Field>
          <Field label="기준 코인">
            <input aria-label="기준 코인" value={coin} onChange={(event) => setCoin(event.target.value.toUpperCase())} />
          </Field>
          <div className="connection-state" aria-label="연결 상태 요약">
            <StatePill label="연결 상태" value={formatExecution(summary.executionAvailable, hasLoaded)} tone={summary.executionAvailable ? "success" : "neutral"} />
            <StatePill label="봇 상태" value={formatBotMode(summary.bot?.mode)} />
            <StatePill label="마지막 갱신" value={hasLoaded ? formatDateTime(account?.capturedAt || summary.bot?.updatedAt) : "조회 전"} />
          </div>
        </section>

        <LiveMessage problemMessage={problemMessage} notice={notice} isLoading={isLoading} />

        <div className="operations-grid">
          <section className="panel account-board" aria-label="계정 현황">
            <PanelHeader
              title="계정 요약"
              description="평가금, 증거금, 미실현 손익을 기준으로 운영 상태를 판단해요."
              action={
                <Button icon={EyeOff} variant="secondary" disabled>
                  금액 숨기기
                </Button>
              }
            />
            <div className="account-metrics">
              <AccountMetric label="총 자산" value={account ? formatMoney(account.totalEquity) : "조회 전"} unit={coin} />
              <AccountMetric label="총 손익" value={account ? formatMoney(account.totalPerpUnrealizedPnl) : "조회 전"} unit={coin} tone={numberTone(account?.totalPerpUnrealizedPnl)} />
              <AccountMetric label="사용 가능 잔고" value={account ? formatMoney(account.totalAvailableBalance) : "조회 전"} unit={coin} />
              <AccountMetric label="지갑 잔고" value={account ? formatMoney(account.totalWalletBalance) : "조회 전"} unit={coin} />
              <AccountMetric label="마진 비율" value="조회 전" unit="" />
              <div className="safety-ring" aria-label="안전 상태">
                <span>확인 전</span>
              </div>
            </div>
          </section>

          <aside className="control-rail">
            <section className="panel bot-board">
              <PanelHeader title="봇 제어 패널" description="진입 가능 여부와 운영 명령을 한 곳에서 처리해요." />
              <Button
                icon={PlayCircle}
                onClick={() => runAction("봇 시작", "/control/resume", { reason: "봇 제어 패널에서 시작" })}
                disabled={isLoading}
              >
                봇 시작
              </Button>
              <section className="strategy-switch" aria-label="전략 프로필">
                <div className="strategy-switch-header">
                  <div>
                    <h3>전략 프로필</h3>
                    <p>{activeProfile?.description || "전략 프로필을 불러오면 현재 적용값이 표시돼요."}</p>
                  </div>
                  <StatusBadge label={runtimeProfile?.name || "공격형"} tone="success" />
                </div>
                <dl className="state-list compact-list">
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
                {activeProfile?.riskNote ? <p className="strategy-note">{activeProfile.riskNote}</p> : false}
              </section>
              <dl className="state-list">
                <StateRow label="봇 상태" value={formatBotMode(summary.bot?.mode)} />
                <StateRow label="실행 모드" value={formatRuntime(summary.runtimeMode)} />
                <StateRow label="계정 연결" value={formatExecution(summary.executionAvailable, hasLoaded)} />
                <StateRow label="최근 변경" value={formatDateTime(summary.bot?.updatedAt)} />
              </dl>
              <div className="button-stack">
                <Button
                  icon={RotateCcw}
                  variant="secondary"
                  onClick={() => runAction("계정 동기화", "/execution/reconcile", { symbol })}
                  disabled={isLoading}
                >
                  동기화
                </Button>
                <Button
                  icon={Send}
                  variant="secondary"
                  onClick={() => {
                    if (
                      !window.confirm(
                        "전략을 평가한 뒤 조건이 맞으면 실제 주문이 제출될 수 있어요. 수동 연결 테스트가 아니라 전략 주문입니다. 계속할까요?",
                      )
                    ) {
                      return;
                    }
                    runAction("평가 후 주문", "/execution/evaluate-and-submit", {
                      symbol,
                      timeframe: "M5",
                      candleLimit: 18000,
                    });
                  }}
                  disabled={isLoading || !strategyEvaluationEnabled}
                >
                  평가 후 주문
                </Button>
                <Button
                  icon={PauseCircle}
                  variant="secondary"
                  onClick={() => runAction("신규 진입 중단", "/control/pause-new-entries", { reason: "봇 제어 패널에서 신규 진입 중단" })}
                  disabled={isLoading}
                >
                  신규 진입 중단
                </Button>
                <Button
                  icon={AlertTriangle}
                  variant="secondary"
                  onClick={() => runAction("긴급 정지", "/control/emergency-stop", { reason: "봇 제어 패널에서 긴급 정지" })}
                  disabled={isLoading}
                >
                  긴급 정지
                </Button>
              </div>
              <section className="smoke-panel" aria-label="실운영 전 기능 테스트">
                <div className="smoke-heading">
                  <h3>기능 테스트</h3>
                  <p>현재 실행 모드에서 알림, 조회, 제어, 수동 주문 경로를 확인해요.</p>
                </div>
                <div className="smoke-controls">
                  <Field label="주문 방향">
                    <select aria-label="주문 방향" value={smokeSide} onChange={(event) => setSmokeSide(event.target.value)}>
                      <option value="BUY">롱</option>
                      <option value="SELL">숏</option>
                    </select>
                  </Field>
                  <Field label="수동 주문 수량">
                    <input
                      aria-label="수동 주문 수량"
                      value={smokeQuantity}
                      inputMode="decimal"
                      onChange={(event) => setSmokeQuantity(event.target.value)}
                    />
                  </Field>
                </div>
                <div className="button-stack smoke-actions">
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
                      runSmokeAction("정지 테스트", "/ops/smoke/control-pause", {
                        reason: "대시보드 TESTNET 정지 테스트",
                      })
                    }
                    disabled={isLoading}
                  >
                    정지 테스트
                  </Button>
                  <Button
                    icon={PlayCircle}
                    variant="secondary"
                    onClick={() =>
                      runSmokeAction("재가동 테스트", "/ops/smoke/control-resume", {
                        reason: "대시보드 TESTNET 재가동 테스트",
                      })
                    }
                    disabled={isLoading}
                  >
                    재가동 테스트
                  </Button>
                  <Button
                    icon={Zap}
                    variant="secondary"
                    onClick={runManualMarketOrder}
                    disabled={isLoading || !manualExchangeEnabled}
                  >
                    수동 시장가 주문
                  </Button>
                </div>
                <SmokeResult result={smokeResult} />
              </section>
            </section>
          </aside>

          <section className="panel positions-board">
            <PanelHeader title="포지션" description="현재 열린 포지션과 미실현 손익이에요." />
            <DataTable
              columns={["심볼", "방향", "수량", "진입가", "현재가", "미실현 손익", "작업"]}
              isLoading={isLoading}
              emptyTitle={emptyTitle(dataState)}
              emptyMessage={emptyMessage(dataState, "열린 포지션이 없어요.", "새로고침하면 열린 포지션이 여기에 표시돼요.")}
            >
              {positions.map((position, index) => (
                <tr key={`${position.symbol}-${position.side}-${index}`}>
                  <td>{position.symbol}</td>
                  <td>
                    <SideText side={position.side} />
                  </td>
                  <td>{formatNumber(position.size)}</td>
                  <td>{formatMoney(position.entryPrice)}</td>
                  <td>{formatMoney(position.markPrice)}</td>
                  <td className={numberClass(position.unrealizedPnl)}>{formatMoney(position.unrealizedPnl)}</td>
                  <td className="table-action-cell">
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

          <section className="panel orders-board">
            <PanelHeader title="미체결 주문" description="거래소에 남아 있는 주문이에요." />
            <DataTable
              columns={["주문 ID", "방향", "유형", "상태", "수량", "생성 시각", "작업"]}
              isLoading={isLoading}
              emptyTitle={emptyTitle(dataState)}
              emptyMessage={emptyMessage(dataState, "미체결 주문이 없어요.", "새로고침하면 미체결 주문이 여기에 표시돼요.")}
            >
              {openOrders.map((order, index) => (
                <tr key={`${order.exchangeOrderId || order.clientOrderId}-${index}`}>
                  <td>{shortId(order.exchangeOrderId || order.clientOrderId)}</td>
                  <td>
                    <SideText side={order.side} />
                  </td>
                  <td>{formatOrderType(order.orderType)}</td>
                  <td>{formatOrderStatus(order.status)}</td>
                  <td>{formatNumber(order.quantity)}</td>
                  <td>{formatDateTime(order.createdAt)}</td>
                  <td className="table-action-cell">
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

          <section className="panel fills-board">
            <PanelHeader title="최근 체결" description="거래소 체결 내역을 시간순으로 확인해요." />
            <DataTable
              columns={["시각", "방향", "가격", "수량", "수수료"]}
              isLoading={isLoading}
              emptyTitle={emptyTitle(dataState)}
              emptyMessage={emptyMessage(dataState, "최근 체결 내역이 없어요.", "새로고침하면 최근 체결 내역이 여기에 표시돼요.")}
            >
              {executions.map((fill, index) => (
                <tr key={`${fill.exchangeOrderId}-${fill.executedAt}-${index}`}>
                  <td>{formatDateTime(fill.executedAt)}</td>
                  <td>
                    <SideText side={fill.side} />
                  </td>
                  <td>{formatMoney(fill.price)}</td>
                  <td>{formatNumber(fill.quantity)}</td>
                  <td>{formatMoney(fill.fee)}</td>
                </tr>
              ))}
            </DataTable>
          </section>

          <section className="panel signals-board">
            <PanelHeader title="전략 신호" description="전략이 남긴 신호와 거절 사유예요." />
            <dl className="strategy-state">
              <StateRow label="현재 신호" value="-" />
              <StateRow label="포지션 추천" value="-" />
              <StateRow label="변경 시간" value="-" />
            </dl>
            <SignalList signals={signals} isLoading={isLoading} dataState={dataState} />
          </section>
        </div>
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

function HeaderMetric({ label, value, tone = "neutral" }) {
  return (
    <div className="header-metric">
      <span>{label}</span>
      <strong className={tone}>{value || "확인 필요"}</strong>
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

function AccountMetric({ label, value, unit, tone = "neutral" }) {
  return (
    <div className="account-metric">
      <span>{label}</span>
      <strong className={tone}>{value}</strong>
      {unit ? <em>{unit}</em> : false}
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
      {Array.from({ length: 5 }).map((_, rowIndex) => (
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
        <li key={signal.id}>
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
    <div className={`smoke-result ${result.status === "PASS" ? "success" : result.status === "FAIL" ? "danger" : "idle"}`}>
      <div>
        <strong>{result.label}</strong>
        <StatusBadge label={formatSmokeStatus(result.status)} tone={result.status === "PASS" ? "success" : result.status === "FAIL" ? "danger" : "neutral"} />
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
  return (
    responseBody?.message ||
    responseBody?.detail ||
    "요청을 마치지 못했어요. 서버 로그와 연결 상태를 확인한 뒤 다시 시도해 주세요."
  );
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
      title:
        pauseConfirmed && resumeRequested
          ? "정지 후 재가동 확인 요청이 접수됐어요."
          : "상태 전환 결과를 확인해 주세요.",
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
      title: isPause
        ? "정지 요청이 처리됐어요."
        : isResumePending
          ? "재가동 확인 요청이 접수됐어요."
          : isResume
            ? "재가동이 확인됐어요."
            : "상태 변경 결과예요.",
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
  if (responseBody.delivery) {
    return {
      title: responseBody.delivery.sinkName === "discord" ? "Discord 전송 결과예요." : "알림 설정을 확인해 주세요.",
      items: [
        {
          label: "전송 채널",
          value: responseBody.delivery.sinkName === "discord" ? "Discord" : "알림 미설정",
          tone: responseBody.delivery.sinkName === "discord" ? "positive" : "neutral",
        },
        {
          label: "전송 결과",
          value: responseBody.delivery.delivered ? "전송 완료" : "전송 실패 · Discord 설정 확인",
          tone: responseBody.delivery.delivered ? "positive" : "negative",
        },
      ],
    };
  }
  if (responseBody.lastPrice) {
    return {
      title: "거래소 조회가 완료됐어요.",
      items: [
        { label: "현재가", value: `${formatMoney(responseBody.lastPrice)} USDT`, tone: "positive" },
        { label: "포지션", value: `${responseBody.positionCount || 0}건`, tone: "neutral" },
        { label: "미체결 주문", value: `${responseBody.openOrderCount || 0}건`, tone: "neutral" },
      ],
    };
  }
  if (responseBody.order) {
    return {
      title: responseBody.order.reduceOnly ? "포지션 청산 주문 요청이 처리됐어요." : "시장가 주문 요청이 처리됐어요.",
      items: [
        { label: "방향", value: formatSide(responseBody.order.side), tone: "neutral" },
        { label: "수량", value: responseBody.order.quantity, tone: "neutral" },
        { label: "상태", value: formatOrderStatus(responseBody.order.status), tone: "positive" },
      ],
    };
  }
  if (responseBody.exchangeOrderId || responseBody.clientOrderId) {
    return {
      title: "주문 취소 요청이 처리됐어요.",
      items: [
        { label: "거래소 주문 ID", value: shortId(responseBody.exchangeOrderId), tone: "neutral" },
        { label: "클라이언트 주문 ID", value: shortId(responseBody.clientOrderId), tone: "neutral" },
      ],
    };
  }
  return false;
}

function emptyTitle(state) {
  if (state === "error") return "연결값을 확인해 주세요";
  if (state === "idle") return "아직 조회하지 않았어요";
  return "표시할 내역이 없어요";
}

function emptyMessage(state, readyMessage, idleMessage) {
  if (state === "error") return "서버 상태와 연결값을 확인한 뒤 다시 새로고침해 주세요.";
  if (state === "idle") return idleMessage;
  return readyMessage;
}

function formatExecution(available, hasLoaded) {
  if (!hasLoaded) return "조회 전";
  return available ? "연결됨" : "연결 안 됨";
}

function formatMoney(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "-";
  return new Intl.NumberFormat("ko-KR", {
    maximumFractionDigits: number >= 100 ? 2 : 6,
  }).format(number);
}

function formatRatioPercent(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "조회 전";
  return `${new Intl.NumberFormat("ko-KR", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(number * 100)}%`;
}

function formatFundingSummary(market) {
  if (!market) return "조회 전";
  const fundingRate = formatRatioPercent(market.fundingRate);
  const nextFunding = formatDateTime(market.nextFundingTime);
  return `${fundingRate} / ${nextFunding}`;
}

function formatNumber(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "-";
  return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 8 }).format(number);
}

function formatDateTime(value) {
  if (!value) return "확인 필요";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "확인 필요";
  return new Intl.DateTimeFormat("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function formatFullDateTime(value) {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return "확인 필요";
  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(date);
}

function formatRuntime(value) {
  if (value === "LIVE") return "실거래";
  if (value === "TESTNET") return "테스트넷";
  if (value === "PAPER") return "모의 실행";
  return "확인 필요";
}

function formatStrategyProfile(profile) {
  if (!profile) return "조회 전";
  return `${profile.name} · ${profile.kindLabel}`;
}

function isManualRuntime(value) {
  return value === "TESTNET" || value === "LIVE";
}

function manualAcknowledgement(runtimeMode, action) {
  return isManualRuntime(runtimeMode) ? `${runtimeMode}_${action}` : "";
}

function formatSide(side) {
  return side === "BUY" ? "롱" : side === "SELL" ? "숏" : side || "확인 필요";
}

function formatBotMode(value) {
  const map = {
    RUNNING: "가동 중",
    PAUSE_NEW_ENTRIES: "신규 진입 중단",
    PAUSE_ALL: "전체 일시정지",
    EMERGENCY_STOP: "긴급 정지",
    RESUME_PENDING_CHECK: "재가동 확인 중",
  };
  return map[value] || value || "확인 필요";
}

function formatSmokeStatus(value) {
  if (value === "PASS") return "성공";
  if (value === "FAIL") return "로그 확인 필요";
  if (value === "RUNNING") return "진행 중";
  return value || "확인 필요";
}

function formatOrderType(value) {
  const map = {
    MARKET: "시장가",
    LIMIT: "지정가",
  };
  return map[value] || value || "확인 필요";
}

function formatOrderStatus(value) {
  const map = {
    SUBMITTED: "제출됨",
    PARTIALLY_FILLED: "일부 체결",
    FILLED: "체결",
    CANCELLED: "취소",
    REJECTED: "거절",
  };
  return map[value] || value || "확인 필요";
}

function shortId(value) {
  if (!value) return "-";
  if (value.length <= 14) return value;
  return `${value.slice(0, 6)}...${value.slice(-5)}`;
}

function isPositiveNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0;
}

function numberTone(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number === 0) return "neutral";
  return number > 0 ? "positive" : "negative";
}

function numberClass(value) {
  return `numeric ${numberTone(value)}`;
}

createRoot(document.getElementById("root")).render(<App />);
