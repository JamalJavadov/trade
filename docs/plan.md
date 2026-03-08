Status: Draft

# Protection Workflow Fix

## Objective
Fix live Binance Futures protection-order orchestration so that a successful manual entry either becomes verifiably protected or persists an explicit protection failure with exact Binance cause, emergency-close outcome, and reconciliation truth.

## Scope
- Manual live execution from recommendation detail
- Post-entry fill detection and protection sizing
- Binance Futures protection-order submission semantics
- Emergency-close fallback behavior
- Reconciliation against Binance order and position truth
- Recommendation detail execution status and timeline rendering

## Non-goals
- No change to strategy logic, recommendation generation, or risk semantics
- No automatic live order placement from scan, autoscan, SSE, or scheduler flows
- No bypass of Control Center, local-only mutation guard, or preflight/placeability checks
- No second Binance client
- No silent conversion of protection failure into success

## Constraints
- Real Binance execution remains MANUAL BUTTON TRIGGERED ONLY
- Existing preflight/placeability remains a hard gate
- Binance is source of truth for final execution state
- Server-enforced invariants remain unchanged:
  - `executionTf = 15m`
  - `biasTf = 1h`
  - `fractalPeriod = 5`
  - `minRr >= 2.0`
  - `maxEquityPctLocked = 1.0`
- Strategy semantics must not change
- Protection failure remains explicit and operator-visible

## Current Failure Summary
- Manual execution request path works and entry submission succeeds
- The latest live execution on March 8, 2026 at 14:46:32 +04:00 failed at protection submission
- Binance returned `400 / -4120` with message: `Order type not supported for this endpoint. Please use the Algo Order API endpoints instead.`
- The same execution attempted protection while the entry submit response was still `NEW` with `executedQty=0`
- The system then submitted an emergency close and later reconciled to flat, but the final state was rendered as a generic reconciled success

## Protection-Order Failure Analysis Plan
- Validate the current live pipeline from request -> preflight -> entry submit -> fill detection -> protection submit -> emergency close -> reconcile -> UI
- Confirm which protection leg failed and preserve per-leg outcome separately
- Replace stale standard-order protection submission with current Binance USD-M Futures algo-order submission
- Preserve the exact Binance rejection payload, endpoint, code, and message in execution state and timeline events

## Entry-Fill vs Protection-Qty Plan
- Submit entry market orders with `newOrderRespType=RESULT`
- Resolve actual fill truth in this order:
  - entry submit response
  - `/fapi/v1/order`
  - `/fapi/v3/positionRisk`
- Persist resolved filled quantity, average entry price, and the source used to derive them
- Build emergency-close quantity from resolved live position size, not intended recommendation size
- Keep protection orders on close-position semantics so they track the actual open position in one-way mode

## Binance Futures Order Semantics Validation Plan
- Keep the existing `BinanceClient`
- Add support for:
  - `POST /fapi/v1/algoOrder`
  - `GET /fapi/v1/algoOrder`
  - `DELETE /fapi/v1/algoOrder`
  - `GET /fapi/v1/openAlgoOrders`
  - `GET /fapi/v3/positionRisk`
- Translate stored protection payloads to current algo-order shape:
  - `algoType=CONDITIONAL`
  - `triggerPrice` derived from stored `stopPrice`
  - one-way `positionSide=BOTH`
  - `closePosition=true`
  - omit `quantity` and `reduceOnly` when `closePosition=true`
- Add runtime validation for:
  - trigger-price tick alignment
  - trigger direction relative to entry side and resolved fill price
  - `closePosition` exclusivity
  - emergency-close quantity rounding to exchange step size

## Emergency-Close Policy Plan
- Policy: stop-loss protection is mandatory
- If stop-loss cannot be confirmed active after entry:
  - persist `PROTECTION_FAILED`
  - persist the failed leg and exact Binance cause
  - submit emergency close using resolved live position quantity
- If stop-loss is active but take-profit fails:
  - keep the position open
  - persist `PROTECTION_FAILED`
  - persist `downsideProtected=true`
  - do not claim protected success

## Reconciliation Plan
- Reconcile standard entry and emergency-close orders through `/fapi/v1/order`
- Reconcile protection legs through `/fapi/v1/algoOrder` and `/fapi/v1/openAlgoOrders`
- Reconcile final exposure through `/fapi/v3/positionRisk`
- Derive operator-visible truth as:
  - non-zero position + both protection legs active -> `PROTECTION_ACTIVE`
  - non-zero position + SL active + TP missing/rejected -> `PROTECTION_FAILED` with `downsideProtected=true`
  - non-zero position + SL missing/rejected -> `PROTECTION_FAILED` and emergency close required
  - zero position after emergency close -> `EMERGENCY_CLOSE_FILLED`
  - zero position after triggered TP/SL -> `RECONCILED`
  - unresolved/timeout -> `RECONCILING`
- Never clear the protection root cause during reconciliation; append reconciliation results instead

## UI Status/Timeline Plan
- Keep the real-execution panel on recommendation detail as the only live order trigger
- Distinguish:
  - entry submitted
  - entry filled / partially filled
  - protection submitting
  - protection active
  - protection failed
  - emergency close submitted
  - emergency close filled / not filled
  - reconciling
- Surface the exact failed protection leg, Binance error code/message, emergency-close outcome, and reconciliation summary
- Remove green success styling from executions that flattened through emergency close
- Continue polling through `PROTECTION_FAILED`, `RECONCILING`, and `EMERGENCY_CLOSE_SUBMITTED`

## Risks
- Binance algo-order response shapes may not match existing order DTO assumptions
- Reusing current protection order id/client id fields requires careful mapping to algo ids/clientAlgoIds
- Position-risk truth can disagree with local order lookup timing during rapid fills
- UI tests need updates because success-like `RECONCILED` no longer covers all acceptable terminal paths

## Acceptance Criteria
- Entry can still be submitted from the manual button path only
- Protection orders are submitted through the correct Binance futures algo-order API
- Protection logic uses resolved exchange fill/position truth
- Protection failures persist exact leg, exact Binance response, and exact reconciliation result
- Emergency close is a visible fallback, not a hidden success path
- Reconciliation follows Binance truth over local assumptions
- Recommendation detail shows accurate execution, protection, emergency-close, and reconciliation state
- Strategy semantics and runtime gates remain unchanged

## Verification Plan
- Backend tests:
  - entry fill -> both protection legs active
  - entry partial fill -> actual size used for emergency close
  - stop-loss rejection -> `PROTECTION_FAILED` -> emergency close submitted
  - stop-loss active / take-profit rejected -> `PROTECTION_FAILED` with `downsideProtected=true`
  - both protection legs rejected -> emergency close submitted and reconciled flat
  - reconciliation preserves protection root cause
  - manual-button-only architecture still passes
- Frontend tests:
  - protected active rendering
  - explicit protection-failure rendering with Binance cause
  - emergency-close-filled rendering distinct from protected success
  - polling continues through protection failure and reconciling states
- Verification commands:
  - `./gradlew build`
  - `./gradlew test --tests com.tradebot.LiveTradingExecutionServiceTest --tests com.tradebot.LiveTradingManualTriggerArchitectureTest --tests com.tradebot.LiveTradingPreflightServiceTest`
  - `cd ui && npm test -- --run src/pages/RecommendationDetailPage.test.tsx`
  - `cd ui && npm run build`
