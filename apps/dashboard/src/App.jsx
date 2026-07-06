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
  Sun,
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

function App() {
  const [apiBase, setApiBase] = useState(() => localStorage.getItem(STORAGE_KEYS.apiBase) || "/api");
  const [accessKey, setAccessKey] = useState(() => localStorage.getItem(STORAGE_KEYS.accessKey) || "");
  const [theme, setTheme] = useState(resolveInitialTheme);
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [coin, setCoin] = useState("USDT");
  const [summary, setSummary] = useState(EMPTY_SUMMARY);
  const [isLoading, setIsLoading] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);
  const [now, setNow] = useState(() => new Date());
  const [problemMessage, setProblemMessage] = useState("");
  const [notice, setNotice] = useState("");

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
        if (!response.ok) {
          throw { message: "데이터를 불러오지 못했어요. 서버 상태를 확인하고 다시 시도해 주세요." };
        }
        return response.json();
      },
    }),
    [accessKey, apiBase],
  );

  const loadSummary = useCallback(async () => {
    setIsLoading(true);
    setProblemMessage("");
    try {
      const nextSummary = await client.request(
        `/dashboard/summary?symbol=${encodeURIComponent(symbol)}&coin=${encodeURIComponent(coin)}&limit=20`,
      );
      setSummary(nextSummary);
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
        setProblemMessage(nextProblem.message || "요청을 마치지 못했어요. 잠시 후 다시 시도해 주세요.");
        return false;
      } finally {
        setIsLoading(false);
      }
    },
    [client, loadSummary],
  );

  const account = summary.account;
  const reconciliation = summary.reconciliation;
  const positions = reconciliation?.positions || [];
  const openOrders = reconciliation?.openOrders || [];
  const executions = reconciliation?.executions || [];
  const signals = summary.recentSignals || [];
  const dataState = problemMessage ? "error" : hasLoaded ? "ready" : "idle";

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
          <HeaderMetric label="마켓" value={symbol} />
          <HeaderMetric label="현재가" value="조회 전" />
          <HeaderMetric label="24H 변화율" value="조회 전" />
          <HeaderMetric label="펀딩 / 카운트다운" value="조회 전" />
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
                  onClick={() =>
                    runAction("평가 후 주문", "/execution/evaluate-and-submit", {
                      symbol,
                      timeframe: "M5",
                      candleLimit: 18000,
                    })
                  }
                  disabled={isLoading}
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
            </section>
          </aside>

          <section className="panel positions-board">
            <PanelHeader title="포지션" description="현재 열린 포지션과 미실현 손익이에요." />
            <DataTable
              columns={["심볼", "방향", "수량", "진입가", "현재가", "미실현 손익"]}
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
                </tr>
              ))}
            </DataTable>
          </section>

          <section className="panel orders-board">
            <PanelHeader title="미체결 주문" description="거래소에 남아 있는 주문이에요." />
            <DataTable
              columns={["주문 ID", "방향", "유형", "상태", "수량", "생성 시각"]}
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

function Button({ children, icon: Icon, variant = "primary", ...props }) {
  return (
    <button className={`button ${variant}`} type="button" {...props}>
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

function HeaderMetric({ label, value }) {
  return (
    <div className="header-metric">
      <span>{label}</span>
      <strong>{value || "확인 필요"}</strong>
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

function formatBotMode(value) {
  const map = {
    RUNNING: "가동 중",
    PAUSE_NEW_ENTRIES: "신규 진입 중단",
    PAUSE_ALL: "전체 일시정지",
    EMERGENCY_STOP: "긴급 정지",
  };
  return map[value] || value || "확인 필요";
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

function numberTone(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number === 0) return "neutral";
  return number > 0 ? "positive" : "negative";
}

function numberClass(value) {
  return `numeric ${numberTone(value)}`;
}

createRoot(document.getElementById("root")).render(<App />);
