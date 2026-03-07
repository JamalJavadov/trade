# Deep Scan Pipeline

## Purpose

The scan engine now uses one shared deep-analysis path for both manual `run-once` scans and scheduled autoscans. The goal is to deepen validation, auditability, and operator trust without changing the deterministic strategy itself.

## Strategy Immutability

The deterministic strategy remains the single source of truth for trade eligibility and ranking. The pipeline preserves:

- market structure logic
- bias logic
- impulse-leg logic
- Fibonacci logic
- sweep/reclaim logic
- entry logic
- stop-loss / take-profit semantics
- risk-sizing semantics
- decision semantics
- server-enforced locks:
  - `executionTf=15m`
  - `biasTf=1h`
  - `fractalPeriod=5`
  - `minRr>=2.0`
  - `maxEquityPctLocked=1.0`
- manual placement only; no auto-trading

AI never redefines strategy rules. AI is limited to review, contradiction detection, and confidence auditing against supplied deterministic evidence.

## Shared Flow

`ScanOrchestrator` still owns run lifecycle, scheduling dedup, single-active-run guarantees, persistence boundaries, and SSE coordination. Per-symbol analysis is delegated to `com.tradebot.service.scan.DeepScanPipelineService`, which is used by both:

- `POST /api/v1/scans/run-once`
- scheduled autoscan execution

The per-symbol pipeline runs on a frozen snapshot built by `MarketSnapshotService`.

## Stages

### 1. Data Integrity & Market Readiness

- validates exchange metadata presence and tradability assumptions
- validates candle freshness, ordering, continuity, and minimum depth
- detects malformed OHLCV values and stale/cutoff snapshots
- fails closed on critical integrity defects
- emits machine-readable findings

Critical integrity failure maps the deterministic outcome to `NO_TRADE` with `skipReasonCode=DATA_ERROR`.

### 2. Deterministic Strategy Evaluation

- runs the existing strategy exactly as implemented
- captures intermediate evidence for bias, impulse, fibs, sweep/reclaim, entry/SL/TP, RR, quantity, and ranking metrics
- keeps legacy decision/ranking semantics unchanged

### 3. Deep Structural Validation

- checks internal consistency of the deterministic result
- validates side/bias consistency, price geometry, fib alignment, reclaim/sweep integrity, RR integrity, and fragile/noisy structure
- can downgrade or block low-integrity setups
- does not introduce new strategy rules

### 4. Second-Pass Deterministic Confirmation

- reruns deterministic evaluation against the same frozen snapshot
- diffs first-pass vs second-pass state and metrics
- records a structured `ConflictReport`
- blocks recommendation eligibility if final deterministic eligibility/state diverges

### 5. AI Comparative Review

- uses `AiTaskType.SCAN_REVIEW`
- uses control-center routing at `ai.live.routing.scanReview` and `ai.demo.routing.scanReview`
- runs a primary reviewer and, when available, a secondary reviewer on distinct models from the allowlist chain
- requires strict JSON output with schema validation
- records model choice, latency, parse validity, errors, verdict, and summaries
- degrades gracefully when AI is unavailable

AI can reduce trust and surface contradictions. AI cannot override a clean deterministic result on its own. Blocking only occurs when major AI contradiction is corroborated by non-AI evidence from integrity, structural validation, or second-pass confirmation.

### 6. Final Recommendation Gate

A recommendation is created only when all of the following hold:

- deterministic decision is valid
- no critical data-integrity failure exists
- structural validation passes
- second-pass confirmation is non-blocking
- static placeability realism passes
- final integrity score meets threshold
- AI does not present a corroborated major contradiction

Recommendation ranking is unchanged: the winning candidate is still the highest deterministic `rrToTp1` among gate-eligible candidates.

## Persistence Model

`symbol_evaluation` now stores additive deep-analysis fields:

- `trace_id`
- `snapshot_json`
- `integrity_json`
- `deterministic_evidence_json`
- `validation_json`
- `confirmation_json`
- `ai_review_json`
- `final_gate_json`
- `recommendation_eligible`
- `final_integrity_score`
- `conflict_state`

`scan_candidate_event` stores candidate-stage replay events keyed by run, symbol, stage, and sequence. This supports replay, SSE resync, and operator audit trails.

Recommendation semantics remain unchanged. Recommendation-level deep context is stored in `recommendation.diagnostics_json`.

## SSE And Replay

Existing scan events remain available. The deep pipeline adds:

- `phase.started`
- `candidate.stage`
- `candidate.ai-reviewed`
- `candidate.gated`

Replay and query APIs now expose:

- deep summary counts
- deep evaluation detail payloads
- candidate-stage event timelines
- AI agreement/disagreement state
- integrity score and gate reasons

## Reliability Constraints

- one active scan at a time remains enforced
- scheduled dedup behavior remains enforced
- deterministic completion path remains available when AI is slow or unavailable
- AI review is bounded and non-authoritative
- fail-closed behavior is preferred on low-integrity or contradictory setups
