package com.tradebot.exception;

import com.tradebot.operator.ForbiddenPermissionException;
import com.tradebot.security.ForbiddenNotLocalException;
import com.tradebot.service.BinanceCredentialException;
import com.tradebot.service.BinanceErrorClassifier;
import com.tradebot.service.BinanceErrorClassifier.BinanceErrorDetails;
import com.tradebot.service.LiveTradingBlockerCodes;
import com.tradebot.trace.TraceIdContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.FlywayException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final Pattern HIKARI_TIMEOUT_PATTERN = Pattern.compile("request timed out after\\s+(\\d+)ms",
            Pattern.CASE_INSENSITIVE);

    @ExceptionHandler(ScanRunningException.class)
    public ResponseEntity<ApiErrorResponse> handleScanRunning(ScanRunningException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("[TraceID: {}] ScanRunningException at {}: {}", traceId, request.getRequestURI(), ex.getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rootMessage", ex.getMessage());
        details.put("retryHint", "Retry after scan finishes.");
        return buildError(request, HttpStatus.CONFLICT, ErrorCode.SCAN_RUNNING,
                ex.getMessage(), details, traceId);
    }

    @ExceptionHandler(ScanWorkerUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleScanWorkerUnavailable(ScanWorkerUnavailableException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("[TraceID: {}] Scan worker unavailable at {}: {}", traceId, request.getRequestURI(), ex.getMessage(),
                ex);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rootMessage", ex.getMessage());
        details.put("retryHint", "Retry shortly; scanner worker is temporarily unavailable.");
        return buildError(request, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SCANNER_DOWN,
                "Scan worker is unavailable.",
                details,
                traceId);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("[TraceID: {}] IllegalStateException at {}: {}", traceId, request.getRequestURI(), ex.getMessage(),
                ex);
        return buildError(request, HttpStatus.CONFLICT, ErrorCode.CONFLICT_OR_STATE_ERROR,
                ex.getMessage(), detailsWithRootMessage(ex.getMessage()), traceId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("[TraceID: {}] IllegalArgumentException at {}: {}", traceId, request.getRequestURI(), ex.getMessage());
        return buildError(request, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION,
                ex.getMessage(), detailsWithRootMessage(ex.getMessage()), traceId);
    }

    @ExceptionHandler(BinanceCredentialException.class)
    public ResponseEntity<ApiErrorResponse> handleBinanceCredentialException(BinanceCredentialException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("[TraceID: {}] BinanceCredentialException at {}: failureCode={} source={} authMode={}",
                traceId,
                request.getRequestURI(),
                ex.getFailureCode(),
                ex.getCredentialSource(),
                ex.getAuthMode());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("credentialSource", ex.getCredentialSource());
        details.put("authMode", ex.getAuthMode());
        details.put("rootMessage", ex.getMessage());
        return buildError(
                request,
                HttpStatus.BAD_REQUEST,
                mapCredentialFailure(ex.getFailureCode()),
                ex.getMessage(),
                details,
                traceId);
    }

    @ExceptionHandler(ForbiddenPermissionException.class)
    public ResponseEntity<ApiErrorResponse> handleForbiddenPermission(ForbiddenPermissionException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("[TraceID: {}] Forbidden permission at {}: permission={}", traceId, request.getRequestURI(),
                ex.getPermissionKey());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("permissionKey", ex.getPermissionKey());
        details.put("title", ex.getTitle());
        details.put("howToFix", "Enable it in Permissions panel");
        return buildError(request, HttpStatus.FORBIDDEN, ErrorCode.RUNTIME_PERMISSION_DISABLED,
                "Action blocked by runtime permission", details, traceId);
    }

    @ExceptionHandler(ForbiddenNotLocalException.class)
    public ResponseEntity<ApiErrorResponse> handleForbiddenNotLocal(ForbiddenNotLocalException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("[TraceID: {}] Forbidden non-local mutation at {} remote={} origin={}",
                traceId, request.getRequestURI(), ex.getRemoteAddress(), ex.getOrigin());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("remoteAddress", ex.getRemoteAddress());
        details.put("origin", ex.getOrigin());
        details.put("howToFix", "Run mutation requests from localhost or localhost-origin UI.");
        return buildError(request, HttpStatus.FORBIDDEN, ErrorCode.LOCAL_MUTATION_BLOCKED,
                "Mutation blocked: endpoint is local-only", details, traceId);
    }

    @ExceptionHandler(BotReadOnlyException.class)
    public ResponseEntity<ApiErrorResponse> handleBotReadOnly(BotReadOnlyException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        return buildError(request,
                HttpStatus.CONFLICT,
                ErrorCode.BOT_READ_ONLY,
                ex.getMessage(),
                detailsWithRootMessage(ex.getMessage()),
                traceId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        String message = ex.getMessage() != null ? ex.getMessage() : "Requested resource was not found.";
        log.warn("[TraceID: {}] NotFound at {}: {}", traceId, request.getRequestURI(), message);
        return buildError(request, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                message, detailsWithRootMessage(message), traceId);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        log.warn("[TraceID: {}] Validation error at {}: {}", traceId, request.getRequestURI(), fieldErrors);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fieldErrors", fieldErrors);
        details.put("rootMessage", "Validation failed for one or more payload fields.");
        return buildError(request, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION,
                "Validation failed for one or more payload fields.", details, traceId);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("[TraceID: {}] Type mismatch at {}: {}", traceId, request.getRequestURI(), ex.getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String fieldName = ex.getName() != null ? ex.getName() : "request";
        fieldErrors.put(fieldName, "Invalid value type.");
        details.put("fieldErrors", fieldErrors);
        details.put("rootMessage", ex.getMessage());
        return buildError(request, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION,
                "Validation failed for one or more payload fields.", details, traceId);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("[TraceID: {}] Message not readable at {}: {}", traceId, request.getRequestURI(), ex.getMessage());
        Throwable mostSpecific = ex.getMostSpecificCause();
        return buildError(request, HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST,
                "Malformed JSON request.",
                detailsWithRootMessage(mostSpecific != null ? mostSpecific.getMessage() : ex.getMessage()),
                traceId);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDataAccessException(DataAccessException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("[TraceID: {}] DB Exception at {}: {}", traceId, request.getRequestURI(), ex.getMessage(), ex);
        return buildError(request, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DB_DOWN,
                "Database error or connectivity issue.",
                databaseDetails(ex),
                traceId);
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<ApiErrorResponse> handleTransactionException(TransactionException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("[TraceID: {}] DB transaction error at {}: {}", traceId, request.getRequestURI(), ex.getMessage(),
                ex);

        return buildError(request, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DB_DOWN,
                "Database transaction failed or database is unavailable.",
                databaseDetails(ex),
                traceId);
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiErrorResponse> handleWebClientResponseException(WebClientResponseException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        String host = ex.getRequest() != null && ex.getRequest().getURI() != null
                ? ex.getRequest().getURI().getHost()
                : "";
        int status = ex.getStatusCode().value();

        ErrorCode mappedCode = ErrorCode.INTERNAL;
        String message = "External API Error";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status);
        details.put("host", host != null ? host : "");
        details.put("rootMessage", ex.getMessage());

        if (host != null && host.contains("binance.com")) {
            BinanceErrorDetails binanceDetails = BinanceErrorClassifier.from(ex);
            ErrorCode classified = switch (BinanceErrorClassifier.classify(binanceDetails)) {
                case LiveTradingBlockerCodes.BINANCE_RATE_LIMIT -> ErrorCode.BINANCE_RATE_LIMIT;
                case LiveTradingBlockerCodes.BINANCE_TIMESTAMP_INVALID -> ErrorCode.BINANCE_TIMESTAMP_INVALID;
                case LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED -> ErrorCode.BINANCE_SIGNING_FAILED;
                case LiveTradingBlockerCodes.BINANCE_ENDPOINT_MISCONFIGURED -> ErrorCode.BINANCE_ENDPOINT_MISCONFIGURED;
                case LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED -> ErrorCode.BINANCE_IP_NOT_ALLOWED;
                case LiveTradingBlockerCodes.BINANCE_FUTURES_PERMISSION_MISSING ->
                    ErrorCode.BINANCE_FUTURES_PERMISSION_MISSING;
                case LiveTradingBlockerCodes.BINANCE_AUTH_INVALID -> ErrorCode.BINANCE_AUTH_INVALID;
                default -> ErrorCode.BINANCE_NETWORK;
            };
            mappedCode = classified;
            message = BinanceErrorClassifier.defaultMessage(BinanceErrorClassifier.classify(binanceDetails), binanceDetails);
            details.put("binanceCode", binanceDetails.binanceCode());
            details.put("binanceMessage", binanceDetails.binanceMessage());
            details.put("requestIpHint", binanceDetails.requestIpHint());
            if (ex.getHeaders().containsKey("Retry-After")) {
                details.put("retryAfter", ex.getHeaders().getFirst("Retry-After"));
            }
        } else if (host != null && host.contains("openrouter.ai")) {
            if (status == 401 || status == 403) {
                mappedCode = ErrorCode.OPENROUTER_AUTH;
                message = "OpenRouter Authentication Failed";
            } else if (status == 429) {
                mappedCode = ErrorCode.OPENROUTER_RATE_LIMIT;
                message = "OpenRouter Rate Limit Exceeded";
                if (ex.getHeaders().containsKey("Retry-After")) {
                    details.put("retryAfter", ex.getHeaders().getFirst("Retry-After"));
                }
            } else if (status >= 500) {
                mappedCode = ErrorCode.OPENROUTER_INTERNAL;
                message = "OpenRouter Internal Error";
            } else {
                mappedCode = ErrorCode.OPENROUTER_NETWORK;
                message = "OpenRouter API request failed.";
            }
        }

        log.error("[TraceID: {}] {} at {}: {}", traceId, mappedCode, request.getRequestURI(), ex.getMessage(), ex);
        HttpStatus statusToReturn = normalizeUpstreamStatus(status);
        if (statusToReturn == HttpStatus.SERVICE_UNAVAILABLE && status >= 200 && status < 400) {
            details.put("upstreamStatus", status);
            details.put("retryHint", "Retry shortly; inspect upstream response size/timeouts if this repeats.");
        }
        return buildError(request, statusToReturn, mappedCode, message, details, traceId);
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<ApiErrorResponse> handleDataBufferLimitException(DataBufferLimitException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("[TraceID: {}] DataBufferLimitException at {}: {}", traceId, request.getRequestURI(),
                ex.getMessage(), ex);
        return buildError(request, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.BINANCE_NETWORK,
                "Upstream response exceeded configured client buffer.",
                detailsWithRootMessage(ex.getMessage()),
                traceId);
    }

    @ExceptionHandler(WebClientRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleWebClientRequestException(WebClientRequestException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        String host = ex.getUri() != null ? ex.getUri().getHost() : "";
        ErrorCode code = ErrorCode.INTERNAL;
        String message = "External API network error";

        if (host != null && host.contains("binance.com")) {
            code = ErrorCode.BINANCE_NETWORK;
            message = "Binance network/timeout failure.";
        } else if (host != null && host.contains("openrouter.ai")) {
            code = ErrorCode.OPENROUTER_NETWORK;
            message = "OpenRouter network/timeout failure.";
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("host", host != null ? host : "");
        details.put("rootMessage", ex.getMessage());

        log.error("[TraceID: {}] {} at {}: {}", traceId, code, request.getRequestURI(), ex.getMessage(), ex);
        return buildError(request, HttpStatus.SERVICE_UNAVAILABLE, code, message, details, traceId);
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleTimeoutException(TimeoutException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("[TraceID: {}] Timeout at {}: {}", traceId, request.getRequestURI(), ex.getMessage(), ex);
        return buildError(request, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL,
                "Upstream request timed out.",
                detailsWithRootMessage(ex.getMessage()),
                traceId);
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderException(AiProviderException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        int status = ex.getHttpStatus() != null ? ex.getHttpStatus() : defaultStatusForAiCode(ex.getErrorCode());
        HttpStatus statusToReturn = HttpStatus.resolve(status);
        if (statusToReturn == null) {
            statusToReturn = HttpStatus.SERVICE_UNAVAILABLE;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", ex.getProvider());
        details.put("status", status);
        details.put("rootMessage", ex.getMessage());
        if (ex.getRetryAfter() != null && !ex.getRetryAfter().isBlank()) {
            details.put("retryAfter", ex.getRetryAfter());
        }

        log.error("[TraceID: {}] {} at {}: {}", traceId, ex.getErrorCode(), request.getRequestURI(), ex.getMessage(),
                ex);
        return buildError(request, statusToReturn, ex.getErrorCode(), ex.getMessage(), details, traceId);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Throwable ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("[TraceID: {}] Unhandled Exception at {}: {}", traceId, request.getRequestURI(), ex.getMessage(), ex);
        return buildError(request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                "An unexpected error occurred.",
                detailsWithRootMessage(ex.getMessage()),
                traceId);
    }

    private ResponseEntity<ApiErrorResponse> buildError(HttpServletRequest request, HttpStatus status, ErrorCode code,
            String message, Object details, String traceId) {
        ApiErrorResponse err = new ApiErrorResponse(
                request.getRequestURI(),
                code.name(),
                message,
                details,
                traceId);
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getResponse() != null) {
            attrs.getResponse().setHeader(TraceIdContext.TRACE_ID_HEADER, traceId);
        }
        return ResponseEntity.status(status).body(err);
    }

    private String traceId(HttpServletRequest request) {
        String traceId = TraceIdContext.resolveOrCreate(request);
        request.setAttribute(TraceIdContext.TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }

    private int defaultStatusForAiCode(ErrorCode code) {
        if (code == ErrorCode.OPENROUTER_AUTH) {
            return HttpStatus.UNAUTHORIZED.value();
        }
        if (code == ErrorCode.OPENROUTER_RATE_LIMIT) {
            return HttpStatus.TOO_MANY_REQUESTS.value();
        }
        if (code == ErrorCode.OPENROUTER_BAD_JSON) {
            return HttpStatus.UNPROCESSABLE_ENTITY.value();
        }
        if (code == ErrorCode.OPENROUTER_INTERNAL) {
            return HttpStatus.BAD_GATEWAY.value();
        }
        return HttpStatus.SERVICE_UNAVAILABLE.value();
    }

    private HttpStatus normalizeUpstreamStatus(int upstreamStatus) {
        HttpStatus resolved = HttpStatus.resolve(upstreamStatus);
        if (resolved == null) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (resolved.is2xxSuccessful() || resolved.is3xxRedirection()) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return resolved;
    }

    private ErrorCode mapCredentialFailure(String failureCode) {
        if (failureCode == null) {
            return ErrorCode.BINANCE_AUTH_INVALID;
        }
        return switch (failureCode) {
            case LiveTradingBlockerCodes.CREDENTIAL_DECRYPT_FAILED -> ErrorCode.CREDENTIAL_DECRYPT_FAILED;
            case LiveTradingBlockerCodes.CREDENTIAL_RECORD_CORRUPT -> ErrorCode.CREDENTIAL_RECORD_CORRUPT;
            case LiveTradingBlockerCodes.CREDENTIAL_AUTH_MODE_UNKNOWN -> ErrorCode.CREDENTIAL_AUTH_MODE_UNKNOWN;
            case LiveTradingBlockerCodes.CREDENTIAL_SOURCE_MISMATCH -> ErrorCode.CREDENTIAL_SOURCE_MISMATCH;
            case LiveTradingBlockerCodes.USER_CONFIGURATION_MISMATCH -> ErrorCode.USER_CONFIGURATION_MISMATCH;
            case LiveTradingBlockerCodes.PLACEHOLDER_CREDENTIALS_DETECTED -> ErrorCode.PLACEHOLDER_CREDENTIALS_DETECTED;
            case LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED -> ErrorCode.BINANCE_SIGNING_FAILED;
            case LiveTradingBlockerCodes.BINANCE_AUTH_INVALID -> ErrorCode.BINANCE_AUTH_INVALID;
            default -> ErrorCode.BINANCE_AUTH_INVALID;
        };
    }

    private Map<String, Object> detailsWithRootMessage(String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rootMessage", message != null ? message : "n/a");
        return details;
    }

    private Map<String, Object> detailsWithFallback(Map<String, Object> details, String fallbackMessage) {
        if (details == null || details.isEmpty()) {
            return detailsWithRootMessage(fallbackMessage);
        }
        if (!details.containsKey("rootMessage")) {
            details.put("rootMessage", fallbackMessage);
        }
        return details;
    }

    private Map<String, Object> databaseDetails(Throwable ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        Throwable rootCause = rootCause(ex);
        String rootMessage = firstNonBlankMessage(rootCause, ex);
        String sqlState = firstSqlState(ex);
        String failureKind = classifyDatabaseFailure(ex, rootCause, sqlState, rootMessage);

        details.put("failureKind", failureKind);
        if (rootCause != null) {
            details.put("rootExceptionClass", rootCause.getClass().getSimpleName());
            if (rootMessage != null && !rootMessage.isBlank()) {
                details.put("rootMessage", rootMessage);
            }
        }
        if (sqlState != null && !sqlState.isBlank()) {
            details.put("sqlState", sqlState);
        }
        Integer acquisitionTimeoutMs = extractHikariTimeoutMillis(ex);
        if (acquisitionTimeoutMs != null) {
            details.put("acquisitionTimeoutMs", acquisitionTimeoutMs);
        }
        String retryHint = databaseRetryHint(failureKind);
        if (retryHint != null) {
            details.put("retryHint", retryHint);
        }
        return detailsWithFallback(details, "Database error or connectivity issue.");
    }

    private Throwable rootCause(Throwable ex) {
        if (ex == null) {
            return null;
        }
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String firstSqlState(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getSQLState() != null
                    && !sqlException.getSQLState().isBlank()) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private String firstNonBlankMessage(Throwable... throwables) {
        if (throwables == null) {
            return null;
        }
        for (Throwable throwable : throwables) {
            if (throwable != null
                    && throwable.getMessage() != null
                    && !throwable.getMessage().isBlank()) {
                return throwable.getMessage();
            }
        }
        return null;
    }

    private String classifyDatabaseFailure(Throwable ex, Throwable rootCause, String sqlState, String rootMessage) {
        if (containsCause(ex, FlywayException.class)) {
            return "MIGRATION_FAILURE";
        }
        if (isPoolExhaustion(ex, rootMessage)) {
            return "POOL_EXHAUSTED";
        }
        if (isAuthenticationFailure(ex, sqlState, rootMessage)) {
            return "AUTH_FAILED";
        }
        if (isNetworkFailure(ex, sqlState, rootCause, rootMessage)) {
            return "UNREACHABLE";
        }
        return "DATABASE_ERROR";
    }

    private boolean isPoolExhaustion(Throwable ex, String rootMessage) {
        return containsMessage(ex, "connection is not available")
                || containsMessage(ex, "threads awaiting connection")
                || containsIgnoreCase(rootMessage, "HikariPool");
    }

    private boolean isAuthenticationFailure(Throwable ex, String sqlState, String rootMessage) {
        return (sqlState != null && sqlState.startsWith("28"))
                || containsMessage(ex, "password authentication failed")
                || containsMessage(ex, "authentication failed")
                || containsMessage(ex, "role does not exist")
                || containsIgnoreCase(rootMessage, "SCRAM");
    }

    private boolean isNetworkFailure(Throwable ex, String sqlState, Throwable rootCause, String rootMessage) {
        return (sqlState != null && sqlState.startsWith("08"))
                || containsCause(ex, ConnectException.class)
                || containsCause(ex, UnknownHostException.class)
                || containsCause(ex, SocketException.class)
                || containsMessage(ex, "connection refused")
                || containsMessage(ex, "connection reset")
                || containsMessage(ex, "connection attempt failed")
                || containsMessage(ex, "the connection attempt failed")
                || containsMessage(ex, "connection to server")
                || containsIgnoreCase(rootMessage, "network adapter");
    }

    private boolean containsCause(Throwable ex, Class<? extends Throwable> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsMessage(Throwable ex, String fragment) {
        Throwable current = ex;
        while (current != null) {
            if (containsIgnoreCase(current.getMessage(), fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsIgnoreCase(String text, String fragment) {
        return text != null
                && fragment != null
                && text.toLowerCase().contains(fragment.toLowerCase());
    }

    private Integer extractHikariTimeoutMillis(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = HIKARI_TIMEOUT_PATTERN.matcher(message);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private String databaseRetryHint(String failureKind) {
        return switch (failureKind) {
            case "POOL_EXHAUSTED" ->
                "The connection pool ran out of available JDBC connections. Check long-running transactions, leaked request-scoped EntityManagers, or stuck async/SSE requests.";
            case "AUTH_FAILED" ->
                "Verify the configured datasource username, password, and database name.";
            case "UNREACHABLE" ->
                "Verify PostgreSQL is running and reachable on the configured host and port.";
            case "MIGRATION_FAILURE" ->
                "Inspect Flyway migration logs and schema history before retrying startup.";
            default -> null;
        };
    }
}
