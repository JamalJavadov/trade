# Plan: Fix Systemic 500s and TraceId Observability

**Status:** Draft

## Objective
Eliminate systemic 500s during DB downtime or empty states, ensure all API responses return structured JSON with `traceId` and `X-Trace-Id` headers, and fix the UI Axios interceptor to capture error payloads instead of falsely reporting "No response from server".

## Constraints
- Do not change strategy outcomes.
- application.yml must only contain DB connection details.
- Must be production-grade: root-cause analysis + tests + proof checklist.

## Root Cause Analysis
- **Missing JSON Errors:** `GlobalExceptionHandler` only catches `Exception`, so `Error` or `java.lang.Throwable` results in Spring's Whitelabel default HTML page. Furthermore, Spring Boot's default error page handles `404` and container-level failures by returning HTML instead of JSON. 
- **Missing TraceId:** When exceptions bypass the `GlobalExceptionHandler`, the `traceId` JSON body field is missing. The UI Axios interceptor receives an HTML body, fails to parse it as `ApiErrorResponse`, and defaults to "No response from server".
- **Systemic 500s:** If the database goes down or `control_center_state` is missing/corrupted, `ControlCenterSettingsProvider` crashes on read, causing `/status`, `/control-center/state`, `/settings`, and `/ai/models` to all fail with 500s because they depend on this config provider throwing an exception or returning null collections (NPE).

## Proposed Changes

### Global Exception & TraceId Infrastructure
- **`src/main/resources/application.yml`**: Add `server.error.whitelabel.enabled: false` and `spring.mvc.problemdetails.enabled: false`.
- **`com/tradebot/config/WebConfig.java`**: Add `.exposedHeaders("X-Trace-Id")` to CORS mapping.
- **`com/tradebot/exception/GlobalExceptionHandler.java`**:
  - Change `@ExceptionHandler(Exception.class)` to `@ExceptionHandler(Throwable.class)` to catch all unhandled throwables.
- **`com/tradebot/config/TraceIdFilter.java`**:
  - Verify `MDC.put` and response header setup.

### Control Center Bootstrap Resiliency
- **`com/tradebot/controlcenter/ControlCenterSettingsProvider.java`**:
  - Wrap `parseConfig` inside a `try/catch` and gracefully return defaults. Force a database update using a `parseFailed` flag.
  - Wrap `loadStateFromDb` inner logic in a `try/catch(Throwable)` returning `new ControlCenterCache.CachedState(defaultConfig(), ...)` to ensure it **never throws** even when DB is down.
  - Protect `allowlist` and `routing` arrays to prevent `NullPointerException` if the DB state was corrupted.

### Endpoint Resilience
- **`com/tradebot/controller/RecommendationController.java`**:
  - Map `/latest` endpoint to return `ResponseEntity<Map<String, Object>>` yielding `{ "recommendation": null }` instead of an empty body when null.
- **`com/tradebot/service/RecommendationQueryService.java`**:
  - Ensure missing related JSONB fields yield `200` with warning, not `500`.

### UI Client Resiliency
- **`ui/src/api/axiosSetup.ts`**:
  - Modify `axios.interceptors.response` to verify `if (error.response)` and parse `X-Trace-Id` regardless of whether `error.response.data` parses as valid JSON.
  - Set details to raw text if parsing fails, never stating "No response from server" if an HTTP status exists.

## Acceptance Criteria
- [ ] DB-first Control Center state always bootstraps without throwing.
- [ ] `/status`, `/control-center/state`, and `/recommendations/latest` return 200 on empty DB.
- [ ] Global exception handler returns true JSON mapping `DB_DOWN`, `NOT_FOUND`, `VALIDATION`, and `INTERNAL`.
- [ ] `X-Trace-Id` is present in HTTP response headers and JSON body for all requests.
- [ ] UI correctly records `traceId` and does not say `No response from server` on 500s.

## Verification Plan

### Backend Tests
- Create or update existing backend integration tests to cover:
  1. Bootstrapping on an empty database (or mock repo) yields defaults without throwing.
  2. `GET /api/v1/recommendations/latest` returns `{"recommendation":null}` on empty DB.
  3. Forced failure throws `Throwable` and returns JSON + `traceId`.

### Manual Proof
- After building and ensuring the database is down or restricted, run:
  - `curl -i http://localhost:8080/api/v1/status`
  - `curl -i http://localhost:8080/api/v1/control-center/state`
  - `curl -i http://localhost:8080/api/v1/recommendations/latest`
- Ensure NO 500 error causes crash and verify the UI Error Center correctly shows trace identifiers.
