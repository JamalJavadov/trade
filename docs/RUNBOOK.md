# TradeBot Runbook

## 1) DB-First Configuration
- Single source of truth: table `control_center_state` (`id=1`, `config_json`, `version`, `updated_at`, `updated_by`).
- All runtime permissions and behavior (scan/risk/alerts/AI routing/demo toggle) are read from DB at runtime.
- `application.yml` is restricted to:
  - `spring.datasource.*`
  - secrets (`OPENROUTER_API_KEY`, `BINANCE_*`, `DEMOBINANCE_*`)
  - `server.port`
  - basic `logging.level`
- On first startup, backend bootstraps non-empty defaults into `control_center_state`.
- DB credentials are configured directly in `src/main/resources/application.yml`.
- Startup validation fails fast if datasource fields are missing or still set to placeholder values.

## 2) Control Center Operations
- UI: `/control-center`
- API read state:
  - `GET /api/v1/control-center/state`
- API patch state:
  - `POST /api/v1/control-center/state`
  - payload:
```json
{
  "patch": {
    "scan": { "intervalMinutes": 20 },
    "demoTrading": { "enabled": false }
  },
  "reason": "operator note"
}
```

## 3) Reset Defaults
- UI method: `Control Center -> Reset to defaults`.
- API method: patch defaults through `POST /api/v1/control-center/state`.
- Lock invariants are always enforced server-side:
  - `risk.maxEquityPctLocked = 1.0`
  - `strategyLocks.minRr >= 2`
  - `strategyLocks.executionTf = 15m`
  - `strategyLocks.biasTf = 1h`
  - `strategyLocks.fractalPeriod = 5`

## 4) Demo Trading One-Toggle
- UI: `/demo`
- Master toggle writes `demoTrading.enabled` in control-center state.
- ON: runtime starts automatically and analytics polling is active.
- OFF: runtime stops and polling pauses.
- Legacy wrapper endpoints (`/api/v1/demo-trading/enable`, `/disable`) delegate to control-center patching.

## 5) Trace-Based Troubleshooting
- Every response includes header `X-Trace-Id`.
- Error envelope format:
```json
{
  "timestamp": "...",
  "path": "/api/v1/...",
  "errorCode": "VALIDATION|FORBIDDEN_PERMISSION|DB_DOWN|...",
  "message": "...",
  "details": {},
  "traceId": "..."
}
```
- Workflow:
  1. Copy `traceId` from UI error banner.
  2. Locate backend logs by that trace id.
  3. Use `errorCode` to apply fix (`VALIDATION`, `FORBIDDEN_PERMISSION`, `DB_DOWN`, `OPENROUTER_AUTH`, etc.).
