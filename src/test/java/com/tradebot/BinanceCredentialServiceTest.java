package com.tradebot;

import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceCredentialRequestDTO;
import com.tradebot.dto.BinanceCredentialResponseDTO;
import com.tradebot.entity.BinanceCredentialEntity;
import com.tradebot.repository.BinanceCredentialRepository;
import com.tradebot.service.BinanceCredentialException;
import com.tradebot.service.BinanceCredentialService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BinanceCredentialServiceTest {

    private static final String VALID_ED25519_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MC4CAQAwBQYDK2VwBCIEIMSvD8pef8Kl/HebOSF69dQbkgOj7xYBDDJvd1+tLM/G
            -----END PRIVATE KEY-----
            """;

    @Test
    void saveCredentialsPersistsPlaintextSecretAndResolvesRoundTrip() {
        BinanceCredentialRepository repository = mock(BinanceCredentialRepository.class);
        AtomicReference<BinanceCredentialEntity> stored = new AtomicReference<>();
        when(repository.findTopByOrderByIdDesc()).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(BinanceCredentialEntity.class))).thenAnswer(invocation -> {
            BinanceCredentialEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            stored.set(entity);
            return entity;
        });

        BinanceCredentialService service = new BinanceCredentialService(repository, new AppProperties());
        BinanceCredentialRequestDTO request = new BinanceCredentialRequestDTO();
        request.setApiKey(" api-key ");
        request.setPrivateKeyPem(" secret-value ");
        request.setAuthMode("HMAC_SECRET");

        BinanceCredentialResponseDTO response = service.saveCredentials(request);
        BinanceCredentialService.ResolvedCredential resolved = service.resolveActiveCredential();

        assertEquals("CONFIGURED", response.getStatus());
        assertEquals("SECURE_UI_SAVED", response.getCredentialSource());
        assertEquals("HMAC_SECRET", response.getAuthMode());
        assertEquals("api-key", stored.get().getApiKey());
        assertEquals("secret-value", stored.get().getPrivateKeyValue());
        assertNull(stored.get().getPrivateKeyEncrypted());
        assertEquals("api-key", resolved.apiKey());
        assertEquals("secret-value", resolved.privateKeyOrSecret());
        assertEquals("HMAC_SECRET", resolved.authMode());
        assertEquals("SECURE_UI_SAVED", resolved.source());
    }

    @Test
    void saveCredentialsPreservesAsymmetricAuthModeAndNormalizesPem() {
        BinanceCredentialRepository repository = mock(BinanceCredentialRepository.class);
        AtomicReference<BinanceCredentialEntity> stored = new AtomicReference<>();
        when(repository.findTopByOrderByIdDesc()).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(BinanceCredentialEntity.class))).thenAnswer(invocation -> {
            BinanceCredentialEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(2L);
            }
            stored.set(entity);
            return entity;
        });

        BinanceCredentialService service = new BinanceCredentialService(repository, new AppProperties());
        BinanceCredentialRequestDTO request = new BinanceCredentialRequestDTO();
        request.setApiKey("asym-key");
        request.setPrivateKeyPem(VALID_ED25519_PRIVATE_KEY.replace("\n", "\\n"));
        request.setAuthMode("ASYMMETRIC_KEYPAIR");

        service.saveCredentials(request);
        BinanceCredentialService.ResolvedCredential resolved = service.resolveActiveCredential();

        assertEquals("ASYMMETRIC_KEYPAIR", stored.get().getAuthMode());
        assertEquals(VALID_ED25519_PRIVATE_KEY.strip(), stored.get().getPrivateKeyValue());
        assertEquals("ASYMMETRIC_KEYPAIR", resolved.authMode());
        assertEquals(VALID_ED25519_PRIVATE_KEY.strip(), resolved.privateKeyOrSecret());
    }

    @Test
    void changingAuthModeRequiresReplacementSecretMaterial() {
        BinanceCredentialRepository repository = mock(BinanceCredentialRepository.class);
        BinanceCredentialEntity existing = new BinanceCredentialEntity();
        existing.setId(3L);
        existing.setApiKey("api-key");
        existing.setAuthMode("HMAC_SECRET");
        existing.setPrivateKeyValue("old-secret");
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(existing));

        BinanceCredentialService service = new BinanceCredentialService(repository, new AppProperties());
        BinanceCredentialRequestDTO request = new BinanceCredentialRequestDTO();
        request.setAuthMode("ASYMMETRIC_KEYPAIR");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveCredentials(request));
        assertEquals("Private Key PEM is required to save or repair ASYMMETRIC_KEYPAIR credentials.",
                error.getMessage());
    }

    @Test
    void yamlFallbackIsUsedOnlyWhenNoDatabaseRecordExists() {
        BinanceCredentialRepository repository = mock(BinanceCredentialRepository.class);
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());

        AppProperties properties = new AppProperties();
        properties.getBinance().setApiKey("yaml-key");
        properties.getBinance().setApiSecret("yaml-secret");

        BinanceCredentialService service = new BinanceCredentialService(repository, properties);
        BinanceCredentialResponseDTO response = service.getStatus();
        BinanceCredentialService.ResolvedCredential resolved = service.resolveActiveCredential();

        assertEquals("CONFIGURED", response.getStatus());
        assertEquals("YAML_LEGACY", response.getCredentialSource());
        assertEquals("HMAC_SECRET", response.getAuthMode());
        assertEquals("yaml-key", resolved.apiKey());
        assertEquals("yaml-secret", resolved.privateKeyOrSecret());
        assertEquals("YAML_LEGACY", resolved.source());
    }

    @Test
    void brokenLegacyDatabaseRecordDoesNotFallBackToYaml() {
        BinanceCredentialRepository repository = mock(BinanceCredentialRepository.class);
        BinanceCredentialEntity existing = new BinanceCredentialEntity();
        existing.setId(4L);
        existing.setApiKey("db-key");
        existing.setAuthMode("ASYMMETRIC_KEYPAIR");
        existing.setPrivateKeyEncrypted("legacy-ciphertext");
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(existing));

        AppProperties properties = new AppProperties();
        properties.getBinance().setApiKey("yaml-key");
        properties.getBinance().setApiSecret("yaml-secret");

        BinanceCredentialService service = new BinanceCredentialService(repository, properties);
        BinanceCredentialResponseDTO response = service.getStatus();
        BinanceCredentialException error = assertThrows(BinanceCredentialException.class, service::resolveActiveCredential);

        assertEquals("BROKEN", response.getStatus());
        assertEquals("SECURE_UI_SAVED", response.getCredentialSource());
        assertEquals("ASYMMETRIC_KEYPAIR", response.getAuthMode());
        assertEquals("CREDENTIAL_DECRYPT_FAILED", response.getFailureCode());
        assertEquals("CREDENTIAL_DECRYPT_FAILED", error.getFailureCode());
        assertEquals("SECURE_UI_SAVED", error.getCredentialSource());
        assertEquals("ASYMMETRIC_KEYPAIR", error.getAuthMode());
    }

    @Test
    void malformedAsymmetricRecordIsReportedAsBrokenInsteadOfConfigured() {
        BinanceCredentialRepository repository = mock(BinanceCredentialRepository.class);
        BinanceCredentialEntity existing = new BinanceCredentialEntity();
        existing.setId(5L);
        existing.setApiKey("api-key");
        existing.setAuthMode("ASYMMETRIC_KEYPAIR");
        existing.setPrivateKeyValue("-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIE|badvalue\n-----END PRIVATE KEY-----");
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(existing));

        BinanceCredentialService service = new BinanceCredentialService(repository, new AppProperties());

        BinanceCredentialResponseDTO response = service.getStatus();
        BinanceCredentialException error = assertThrows(BinanceCredentialException.class, service::resolveActiveCredential);

        assertEquals("BROKEN", response.getStatus());
        assertEquals("USER_CONFIGURATION_MISMATCH", response.getFailureCode());
        assertEquals("ASYMMETRIC_KEYPAIR", response.getAuthMode());
        assertEquals("SECURE_UI_SAVED", response.getCredentialSource());
        assertEquals("USER_CONFIGURATION_MISMATCH", error.getFailureCode());
    }
}
