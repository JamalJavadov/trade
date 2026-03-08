package com.tradebot.service;

import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceCredentialRequestDTO;
import com.tradebot.dto.BinanceCredentialResponseDTO;
import com.tradebot.entity.BinanceCredentialEntity;
import com.tradebot.repository.BinanceCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class BinanceCredentialService {

    public static final String STATUS_CONFIGURED = "CONFIGURED";
    public static final String STATUS_BROKEN = "BROKEN";
    public static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";

    public static final String SOURCE_SECURE_UI_SAVED = "SECURE_UI_SAVED";
    public static final String SOURCE_YAML_LEGACY = "YAML_LEGACY";
    public static final String SOURCE_NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String SOURCE_REQUEST_PAYLOAD = "REQUEST_PAYLOAD";

    public static final String AUTH_MODE_HMAC_SECRET = "HMAC_SECRET";
    public static final String AUTH_MODE_ASYMMETRIC_KEYPAIR = "ASYMMETRIC_KEYPAIR";
    public static final String AUTH_MODE_UNKNOWN = "UNKNOWN";

    public static final String FAILURE_CREDENTIAL_DECRYPT_FAILED = "CREDENTIAL_DECRYPT_FAILED";
    public static final String FAILURE_CREDENTIAL_RECORD_CORRUPT = "CREDENTIAL_RECORD_CORRUPT";
    public static final String FAILURE_CREDENTIAL_AUTH_MODE_UNKNOWN = "CREDENTIAL_AUTH_MODE_UNKNOWN";
    public static final String FAILURE_CREDENTIAL_SOURCE_MISMATCH = "CREDENTIAL_SOURCE_MISMATCH";
    public static final String FAILURE_USER_CONFIGURATION_MISMATCH = "USER_CONFIGURATION_MISMATCH";
    public static final String FAILURE_PLACEHOLDER_CREDENTIALS_DETECTED = "PLACEHOLDER_CREDENTIALS_DETECTED";

    public record ResolvedCredential(String apiKey, String privateKeyOrSecret, String authMode, String source) {
    }

    public record CredentialStatusSnapshot(
            String status,
            String credentialSource,
            String authMode,
            Instant updatedAt,
            String failureCode,
            String failureMessage) {
    }

    private record LoadedCredentialState(
            CredentialStatusSnapshot snapshot,
            ResolvedCredential credential) {
    }

    private final BinanceCredentialRepository repository;
    private final AppProperties appProperties;

    public BinanceCredentialService(BinanceCredentialRepository repository, AppProperties appProperties) {
        this.repository = repository;
        this.appProperties = appProperties;
    }

    @Transactional
    public BinanceCredentialResponseDTO saveCredentials(BinanceCredentialRequestDTO request) {
        BinanceCredentialEntity entity = repository.findTopByOrderByIdDesc().orElse(new BinanceCredentialEntity());
        boolean isNew = entity.getId() == null;

        String existingAuthMode = normalizeAuthModeOrNull(entity.getAuthMode());
        String targetAuthMode = selectAuthModeForSave(request, entity);
        boolean authModeChanged = !isNew
                && existingAuthMode != null
                && !existingAuthMode.equals(targetAuthMode);

        String apiKey = resolveApiKeyForSave(request, entity);
        String privateKeyValue = resolvePrivateKeyForSave(request, entity, targetAuthMode, authModeChanged);
        BinanceSigningSupport.ValidatedCredential validated = validateCredential(
                apiKey,
                privateKeyValue,
                targetAuthMode,
                SOURCE_SECURE_UI_SAVED);

        entity.setApiKey(validated.apiKey());
        entity.setAuthMode(targetAuthMode);
        entity.setPrivateKeyValue(validated.normalizedPrivateMaterial());
        entity.setPrivateKeyEncrypted(null);

        if (AUTH_MODE_ASYMMETRIC_KEYPAIR.equals(targetAuthMode)) {
            entity.setPublicKeyPem(resolvePublicKeyForSave(request, entity));
        } else {
            entity.setPublicKeyPem(null);
        }

        repository.save(entity);
        return getStatus();
    }

    @Transactional(readOnly = true)
    public BinanceCredentialResponseDTO getStatus() {
        return toResponse(loadActiveState().snapshot());
    }

    @Transactional(readOnly = true)
    public CredentialStatusSnapshot inspectActiveCredential() {
        return loadActiveState().snapshot();
    }

    @Transactional(readOnly = true)
    public ResolvedCredential resolveActiveCredential() {
        LoadedCredentialState state = loadActiveState();
        if (state.credential() != null) {
            return state.credential();
        }
        throw toCredentialException(state.snapshot());
    }

    @Transactional(readOnly = true)
    public ResolvedCredential resolveCredentialForTest(BinanceCredentialRequestDTO request) {
        if (!hasAnyTestOverride(request)) {
            return resolveActiveCredential();
        }

        Optional<BinanceCredentialEntity> persisted = repository.findTopByOrderByIdDesc();
        String baseAuthMode = persisted.map(BinanceCredentialEntity::getAuthMode).orElse(AUTH_MODE_HMAC_SECRET);
        String targetAuthMode = normalizeAuthModeOrThrow(
                hasText(request.getAuthMode()) ? request.getAuthMode() : baseAuthMode,
                SOURCE_REQUEST_PAYLOAD);

        String persistedAuthMode = persisted.map(BinanceCredentialEntity::getAuthMode)
                .map(this::normalizeAuthModeOrNull)
                .orElseGet(() -> hasYamlCredentials() ? AUTH_MODE_HMAC_SECRET : null);
        boolean authModeChanged = persistedAuthMode != null && !persistedAuthMode.equals(targetAuthMode);

        String apiKey = hasText(request.getApiKey())
                ? request.getApiKey().trim()
                : persisted.map(BinanceCredentialEntity::getApiKey).filter(this::hasText).map(String::trim)
                        .orElseGet(() -> yamlValue(appProperties.getBinance().getApiKey()));
        if (!hasText(apiKey)) {
            throw new BinanceCredentialException(
                    FAILURE_CREDENTIAL_RECORD_CORRUPT,
                    "Binance API key is missing. Enter an API key or re-save credentials in Settings.",
                    SOURCE_REQUEST_PAYLOAD,
                    targetAuthMode);
        }

        String privateKeyValue;
        if (hasText(request.getPrivateKeyPem())) {
            privateKeyValue = normalizePrivateMaterial(request.getPrivateKeyPem(), targetAuthMode);
        } else {
            if (authModeChanged) {
                throw new BinanceCredentialException(
                        FAILURE_CREDENTIAL_SOURCE_MISMATCH,
                        "Unsaved test credentials do not match the active saved auth mode. Enter the full credential pair for the selected auth mode or test the saved record.",
                        SOURCE_REQUEST_PAYLOAD,
                        targetAuthMode);
            }
            privateKeyValue = resolvePersistedOrYamlSecret(persisted, targetAuthMode, SOURCE_REQUEST_PAYLOAD);
        }

        if (!hasText(privateKeyValue)) {
            throw new BinanceCredentialException(
                    FAILURE_CREDENTIAL_RECORD_CORRUPT,
                    requiredSecretMessage(targetAuthMode, persisted.isPresent()),
                    SOURCE_REQUEST_PAYLOAD,
                    targetAuthMode);
        }

        BinanceSigningSupport.ValidatedCredential validated = validateCredential(
                apiKey,
                privateKeyValue,
                targetAuthMode,
                SOURCE_REQUEST_PAYLOAD);
        return new ResolvedCredential(
                validated.apiKey(),
                validated.normalizedPrivateMaterial(),
                targetAuthMode,
                SOURCE_REQUEST_PAYLOAD);
    }

    private LoadedCredentialState loadActiveState() {
        Optional<BinanceCredentialEntity> persisted = repository.findTopByOrderByIdDesc();
        if (persisted.isPresent()) {
            return stateFromPersistedRecord(persisted.get());
        }
        return stateFromYaml();
    }

    private LoadedCredentialState stateFromPersistedRecord(BinanceCredentialEntity entity) {
        Instant updatedAt = entity.getUpdatedAt();
        String normalizedAuthMode = normalizeAuthModeOrNull(entity.getAuthMode());
        String authMode = normalizedAuthMode != null ? normalizedAuthMode : AUTH_MODE_UNKNOWN;

        if (normalizedAuthMode == null) {
            return brokenState(
                    SOURCE_SECURE_UI_SAVED,
                    AUTH_MODE_UNKNOWN,
                    updatedAt,
                    FAILURE_CREDENTIAL_AUTH_MODE_UNKNOWN,
                    "Stored credential auth mode is invalid. Please update credentials.");
        }

        if (!hasText(entity.getApiKey())) {
            return brokenState(
                    SOURCE_SECURE_UI_SAVED,
                    authMode,
                    updatedAt,
                    FAILURE_CREDENTIAL_RECORD_CORRUPT,
                    "Saved Binance credentials are incomplete. Please re-save them.");
        }

        if (hasText(entity.getPrivateKeyValue())) {
            try {
                BinanceSigningSupport.ValidatedCredential validated = validateCredential(
                        entity.getApiKey(),
                        entity.getPrivateKeyValue(),
                        authMode,
                        SOURCE_SECURE_UI_SAVED);
                return configuredState(
                        new ResolvedCredential(
                                validated.apiKey(),
                                validated.normalizedPrivateMaterial(),
                                authMode,
                                SOURCE_SECURE_UI_SAVED),
                        updatedAt);
            } catch (BinanceCredentialException ex) {
                return brokenState(
                        SOURCE_SECURE_UI_SAVED,
                        authMode,
                        updatedAt,
                        ex.getFailureCode(),
                        ex.getMessage());
            }
        }

        if (hasText(entity.getPrivateKeyEncrypted())) {
            return brokenState(
                    SOURCE_SECURE_UI_SAVED,
                    authMode,
                    updatedAt,
                    FAILURE_CREDENTIAL_DECRYPT_FAILED,
                    "Saved Binance credentials are unreadable. Please re-save them.");
        }

        return brokenState(
                SOURCE_SECURE_UI_SAVED,
                authMode,
                updatedAt,
                FAILURE_CREDENTIAL_RECORD_CORRUPT,
                "Saved Binance credentials are incomplete. Please re-save them.");
    }

    private LoadedCredentialState stateFromYaml() {
        String apiKey = yamlValue(appProperties.getBinance().getApiKey());
        String apiSecret = yamlValue(appProperties.getBinance().getApiSecret());
        if (!hasText(apiKey) && !hasText(apiSecret)) {
            return new LoadedCredentialState(
                    new CredentialStatusSnapshot(
                            STATUS_NOT_CONFIGURED,
                            SOURCE_NOT_CONFIGURED,
                            null,
                            null,
                            null,
                            null),
                    null);
        }
        if (!hasText(apiKey) || !hasText(apiSecret)) {
            return brokenState(
                    SOURCE_YAML_LEGACY,
                    AUTH_MODE_HMAC_SECRET,
                    null,
                    FAILURE_CREDENTIAL_RECORD_CORRUPT,
                    "Legacy YAML Binance credentials are incomplete. Fix the local config or save credentials in Settings.");
        }
        try {
            BinanceSigningSupport.ValidatedCredential validated = validateCredential(
                    apiKey,
                    apiSecret,
                    AUTH_MODE_HMAC_SECRET,
                    SOURCE_YAML_LEGACY);
            return configuredState(
                    new ResolvedCredential(
                            validated.apiKey(),
                            validated.normalizedPrivateMaterial(),
                            AUTH_MODE_HMAC_SECRET,
                            SOURCE_YAML_LEGACY),
                    null);
        } catch (BinanceCredentialException ex) {
            return brokenState(
                    SOURCE_YAML_LEGACY,
                    AUTH_MODE_HMAC_SECRET,
                    null,
                    ex.getFailureCode(),
                    ex.getMessage());
        }
    }

    private LoadedCredentialState configuredState(ResolvedCredential credential, Instant updatedAt) {
        return new LoadedCredentialState(
                new CredentialStatusSnapshot(
                        STATUS_CONFIGURED,
                        credential.source(),
                        credential.authMode(),
                        updatedAt,
                        null,
                        null),
                credential);
    }

    private LoadedCredentialState brokenState(String source,
            String authMode,
            Instant updatedAt,
            String failureCode,
            String failureMessage) {
        return new LoadedCredentialState(
                new CredentialStatusSnapshot(
                        STATUS_BROKEN,
                        source,
                        authMode,
                        updatedAt,
                        failureCode,
                        failureMessage),
                null);
    }

    private BinanceCredentialResponseDTO toResponse(CredentialStatusSnapshot snapshot) {
        BinanceCredentialResponseDTO response = new BinanceCredentialResponseDTO();
        response.setStatus(snapshot.status());
        response.setCredentialSource(snapshot.credentialSource());
        response.setAuthMode(snapshot.authMode());
        response.setEndpointFamily("BINANCE_FUTURES");
        response.setUpdatedAt(snapshot.updatedAt());
        response.setFailureCode(snapshot.failureCode());
        response.setFailureMessage(snapshot.failureMessage());
        return response;
    }

    private BinanceCredentialException toCredentialException(CredentialStatusSnapshot snapshot) {
        String failureCode = snapshot.failureCode() != null
                ? snapshot.failureCode()
                : LiveTradingBlockerCodes.BINANCE_AUTH_INVALID;
        String failureMessage = snapshot.failureMessage() != null
                ? snapshot.failureMessage()
                : "Binance trading credentials are missing.";
        return new BinanceCredentialException(
                failureCode,
                failureMessage,
                snapshot.credentialSource(),
                snapshot.authMode());
    }

    private String selectAuthModeForSave(BinanceCredentialRequestDTO request, BinanceCredentialEntity entity) {
        if (hasText(request.getAuthMode())) {
            return normalizeAuthModeForSave(request.getAuthMode());
        }
        if (hasText(entity.getAuthMode())) {
            return normalizeAuthModeForSave(entity.getAuthMode());
        }
        throw new IllegalArgumentException("Auth Mode is required");
    }

    private String resolveApiKeyForSave(BinanceCredentialRequestDTO request, BinanceCredentialEntity entity) {
        if (hasText(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        if (hasText(entity.getApiKey())) {
            return entity.getApiKey().trim();
        }
        throw new IllegalArgumentException("API Key is required");
    }

    private String resolvePrivateKeyForSave(BinanceCredentialRequestDTO request,
            BinanceCredentialEntity entity,
            String targetAuthMode,
            boolean authModeChanged) {
        if (hasText(request.getPrivateKeyPem())) {
            return normalizePrivateMaterial(request.getPrivateKeyPem(), targetAuthMode);
        }
        if (authModeChanged) {
            throw new IllegalArgumentException(requiredSecretMessage(targetAuthMode, true));
        }
        if (hasText(entity.getPrivateKeyValue())) {
            return entity.getPrivateKeyValue();
        }
        throw new IllegalArgumentException(requiredSecretMessage(targetAuthMode, entity.getId() != null));
    }

    private String resolvePublicKeyForSave(BinanceCredentialRequestDTO request, BinanceCredentialEntity entity) {
        if (!hasText(request.getPublicKeyPem())) {
            return entity.getPublicKeyPem();
        }
        if ("CLEAR".equalsIgnoreCase(request.getPublicKeyPem().trim())) {
            return null;
        }
        return normalizePem(request.getPublicKeyPem());
    }

    private String resolvePersistedOrYamlSecret(Optional<BinanceCredentialEntity> persisted,
            String authMode,
            String failureSource) {
        if (persisted.isPresent()) {
            BinanceCredentialEntity entity = persisted.get();
            if (hasText(entity.getPrivateKeyValue())) {
                return entity.getPrivateKeyValue();
            }
            if (hasText(entity.getPrivateKeyEncrypted())) {
                throw new BinanceCredentialException(
                        FAILURE_CREDENTIAL_DECRYPT_FAILED,
                        "Saved Binance credentials are unreadable. Please re-save them.",
                        SOURCE_SECURE_UI_SAVED,
                        authMode);
            }
            throw new BinanceCredentialException(
                    FAILURE_CREDENTIAL_RECORD_CORRUPT,
                    "Saved Binance credentials are incomplete. Please re-save them.",
                    SOURCE_SECURE_UI_SAVED,
                    authMode);
        }
        if (AUTH_MODE_HMAC_SECRET.equals(authMode) && hasYamlCredentials()) {
            return yamlValue(appProperties.getBinance().getApiSecret());
        }
        throw new BinanceCredentialException(
                FAILURE_CREDENTIAL_RECORD_CORRUPT,
                requiredSecretMessage(authMode, false),
                failureSource,
                authMode);
    }

    private boolean hasYamlCredentials() {
        return hasText(appProperties.getBinance().getApiKey()) && hasText(appProperties.getBinance().getApiSecret());
    }

    private String yamlValue(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String normalizeAuthModeOrThrow(String rawAuthMode, String source) {
        String authMode = normalizeAuthModeOrNull(rawAuthMode);
        if (authMode != null) {
            return authMode;
        }
        throw new BinanceCredentialException(
                FAILURE_CREDENTIAL_AUTH_MODE_UNKNOWN,
                "Credential auth mode is inconsistent. Please update credentials.",
                source,
                AUTH_MODE_UNKNOWN);
    }

    private String normalizeAuthModeForSave(String rawAuthMode) {
        String authMode = normalizeAuthModeOrNull(rawAuthMode);
        if (authMode != null) {
            return authMode;
        }
        throw new IllegalArgumentException("Unsupported auth mode: " + rawAuthMode);
    }

    private String normalizeAuthModeOrNull(String rawAuthMode) {
        if (!hasText(rawAuthMode)) {
            return null;
        }
        String normalized = rawAuthMode.trim().toUpperCase(Locale.ROOT);
        if (AUTH_MODE_HMAC_SECRET.equals(normalized)) {
            return AUTH_MODE_HMAC_SECRET;
        }
        if (AUTH_MODE_ASYMMETRIC_KEYPAIR.equals(normalized)) {
            return AUTH_MODE_ASYMMETRIC_KEYPAIR;
        }
        return null;
    }

    private String normalizePrivateMaterial(String rawValue, String authMode) {
        if (AUTH_MODE_ASYMMETRIC_KEYPAIR.equals(authMode)) {
            return normalizePem(rawValue);
        }
        return rawValue.trim();
    }

    private String normalizePem(String pem) {
        return BinanceSigningSupport.normalizePem(pem);
    }

    private boolean hasAnyTestOverride(BinanceCredentialRequestDTO request) {
        return request != null
                && (hasText(request.getApiKey()) || hasText(request.getPrivateKeyPem()) || hasText(request.getAuthMode())
                        || hasText(request.getPublicKeyPem()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String requiredSecretMessage(String authMode, boolean updatingExistingRecord) {
        if (AUTH_MODE_ASYMMETRIC_KEYPAIR.equals(authMode)) {
            return updatingExistingRecord
                    ? "Private Key PEM is required to save or repair ASYMMETRIC_KEYPAIR credentials."
                    : "Private Key PEM is required for ASYMMETRIC_KEYPAIR mode.";
        }
        return updatingExistingRecord
                ? "API Secret is required to save or repair HMAC_SECRET credentials."
                : "API Secret is required for HMAC_SECRET mode.";
    }

    private BinanceSigningSupport.ValidatedCredential validateCredential(String apiKey,
            String privateMaterial,
            String authMode,
            String credentialSource) {
        try {
            return BinanceSigningSupport.validateCredential(apiKey, privateMaterial, authMode, credentialSource);
        } catch (BinanceCredentialException ex) {
            if (LiveTradingBlockerCodes.USER_CONFIGURATION_MISMATCH.equals(ex.getFailureCode())) {
                throw new BinanceCredentialException(
                        FAILURE_USER_CONFIGURATION_MISMATCH,
                        ex.getMessage(),
                        ex.getCredentialSource(),
                        ex.getAuthMode());
            }
            if (LiveTradingBlockerCodes.PLACEHOLDER_CREDENTIALS_DETECTED.equals(ex.getFailureCode())) {
                throw new BinanceCredentialException(
                        FAILURE_PLACEHOLDER_CREDENTIALS_DETECTED,
                        ex.getMessage(),
                        ex.getCredentialSource(),
                        ex.getAuthMode());
            }
            throw ex;
        }
    }
}
