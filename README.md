# TradeBot Auto-Scanner
Local Spring Boot + React system for SM-Fib Sweep Continuation with live scan, demo trading, AI suggestions, replay, and operator controls.

## Quick Start
1. Copy `src/main/resources/application.yml.example` to `src/main/resources/application.yml`.
2. Set your local Postgres credentials directly in `application.yml` (`spring.datasource.url`, `username`, `password`).
3. Fill required API secrets in `application.yml` as needed.
4. Run `./start-all.sh`.
5. Open UI at [http://localhost:5173](http://localhost:5173).

## DB-First Runtime Model
- Runtime permissions and behavior are stored in DB table `control_center_state` (`id=1`) in `config_json`.
- `application.yml` is intentionally limited to:
  - `spring.datasource.*`
  - secrets (`OPENROUTER_API_KEY`, `BINANCE_*`, `DEMOBINANCE_*`)
  - `server.port`
  - basic `logging.level`
- At startup, backend bootstraps defaults into `control_center_state` if missing.

## Control Center
- Canonical UI route: `/control-center`.
- Legacy `/permissions` now redirects to `/control-center`.
- Backend API:
  - `GET /api/v1/control-center/state`
  - `POST /api/v1/control-center/state` with `{ "patch": { ... }, "reason": "..." }`

## Demo Trading
- `/demo` uses one master toggle: `demoTrading.enabled`.
- Toggle ON/OFF updates control-center state; runtime starts/stops without restart.
- Demo remains isolated to `demo_*` tables and `/api/v1/demo-trading/*` APIs.
- No real order placement is performed.

## Runbook and Security
- [Runbook](./docs/RUNBOOK.md)
- [Security](./docs/SECURITY.md)
- [Troubleshooting](./docs/TROUBLESHOOTING.md)
