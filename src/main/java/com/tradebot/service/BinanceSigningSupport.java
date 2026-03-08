package com.tradebot.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class BinanceSigningSupport {

    public static final String KEY_ALGORITHM_HMAC = "HMAC_SHA256";
    public static final String KEY_ALGORITHM_ED25519 = "ED25519";
    public static final String KEY_ALGORITHM_RSA = "RSA";

    private static final List<String> PLACEHOLDER_HINTS = List.of(
            "enter api",
            "enter secret",
            "enter private",
            "your_api",
            "your-secret",
            "replace_me",
            "replace-with",
            "placeholder",
            "<api",
            "<secret",
            "<private");

    private BinanceSigningSupport() {
    }

    public record ValidatedCredential(
            String apiKey,
            String normalizedPrivateMaterial,
            String authMode,
            String keyAlgorithm) {
    }

    private record ValidatedPrivateMaterial(
            String normalizedPrivateMaterial,
            String authMode,
            String keyAlgorithm) {
    }

    public static ValidatedCredential validateCredential(String apiKey,
            String privateMaterial,
            String authMode,
            String credentialSource) {
        if (!hasText(apiKey)) {
            throw new BinanceCredentialException(
                    LiveTradingBlockerCodes.CREDENTIAL_RECORD_CORRUPT,
                    "Binance API key is missing. Re-save credentials in Control Center.",
                    credentialSource,
                    authMode);
        }

        if (!hasText(privateMaterial)) {
            throw new BinanceCredentialException(
                    LiveTradingBlockerCodes.CREDENTIAL_RECORD_CORRUPT,
                    missingPrivateMaterialMessage(authMode),
                    credentialSource,
                    authMode);
        }

        String normalizedApiKey = apiKey.trim();
        if (looksLikePlaceholder(normalizedApiKey) || looksLikePlaceholder(privateMaterial)) {
            throw new BinanceCredentialException(
                    LiveTradingBlockerCodes.PLACEHOLDER_CREDENTIALS_DETECTED,
                    "The configured Binance credentials appear to contain placeholder or demo values.",
                    credentialSource,
                    authMode);
        }

        ValidatedPrivateMaterial validatedPrivateMaterial = validatePrivateMaterial(privateMaterial, authMode, credentialSource);
        return new ValidatedCredential(
                normalizedApiKey,
                validatedPrivateMaterial.normalizedPrivateMaterial(),
                authMode,
                validatedPrivateMaterial.keyAlgorithm());
    }

    public static String sign(String payload,
            String privateMaterial,
            String authMode,
            String credentialSource) {
        ValidatedPrivateMaterial validated = validatePrivateMaterial(privateMaterial, authMode, credentialSource);
        try {
            if (BinanceCredentialService.AUTH_MODE_ASYMMETRIC_KEYPAIR.equals(validated.authMode())) {
                return signAsymmetric(payload, validated.normalizedPrivateMaterial(), validated.keyAlgorithm());
            }
            return signHmac(payload, validated.normalizedPrivateMaterial());
        } catch (BinanceCredentialException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "The backend could not sign the Binance request for the selected credential type.",
                    ex);
        }
    }

    public static String normalizePem(String pem) {
        return pem.trim().replace("\\n", "\n").replace("\r\n", "\n");
    }

    private static ValidatedPrivateMaterial validatePrivateMaterial(String privateMaterial,
            String authMode,
            String credentialSource) {
        if (!hasText(privateMaterial)) {
            throw new BinanceCredentialException(
                    LiveTradingBlockerCodes.CREDENTIAL_RECORD_CORRUPT,
                    missingPrivateMaterialMessage(authMode),
                    credentialSource,
                    authMode);
        }
        if (BinanceCredentialService.AUTH_MODE_HMAC_SECRET.equals(authMode)) {
            return new ValidatedPrivateMaterial(privateMaterial.trim(), authMode, KEY_ALGORITHM_HMAC);
        }

        if (!BinanceCredentialService.AUTH_MODE_ASYMMETRIC_KEYPAIR.equals(authMode)) {
            throw new BinanceCredentialException(
                    LiveTradingBlockerCodes.CREDENTIAL_AUTH_MODE_UNKNOWN,
                    "Credential auth mode is inconsistent. Please update credentials.",
                    credentialSource,
                    BinanceCredentialService.AUTH_MODE_UNKNOWN);
        }

        String normalizedPem = normalizePem(privateMaterial);
        byte[] decodedPkcs8 = decodePkcs8Body(normalizedPem, credentialSource, authMode);
        String keyAlgorithm = detectAsymmetricAlgorithm(decodedPkcs8, credentialSource, authMode);
        return new ValidatedPrivateMaterial(normalizedPem, authMode, keyAlgorithm);
    }

    private static byte[] decodePkcs8Body(String normalizedPem,
            String credentialSource,
            String authMode) {
        if (!normalizedPem.contains("BEGIN PRIVATE KEY") || !normalizedPem.contains("END PRIVATE KEY")) {
            throw invalidAsymmetricCredential(credentialSource, authMode);
        }

        String pemBody = normalizedPem
                .replaceAll("-----BEGIN.*?-----", "")
                .replaceAll("-----END.*?-----", "")
                .replaceAll("\\s", "");

        if (!hasText(pemBody)) {
            throw invalidAsymmetricCredential(credentialSource, authMode);
        }

        for (int index = 0; index < pemBody.length(); index++) {
            char current = pemBody.charAt(index);
            boolean validChar = (current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '+'
                    || current == '/'
                    || current == '=';
            if (!validChar) {
                throw invalidAsymmetricCredential(credentialSource, authMode);
            }
        }

        try {
            return Base64.getDecoder().decode(pemBody);
        } catch (IllegalArgumentException ex) {
            throw invalidAsymmetricCredential(credentialSource, authMode);
        }
    }

    private static String detectAsymmetricAlgorithm(byte[] decodedPkcs8,
            String credentialSource,
            String authMode) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decodedPkcs8);
        if (canLoadPrivateKey(spec, "Ed25519")) {
            return KEY_ALGORITHM_ED25519;
        }
        if (canLoadPrivateKey(spec, "RSA")) {
            return KEY_ALGORITHM_RSA;
        }
        throw invalidAsymmetricCredential(credentialSource, authMode);
    }

    private static boolean canLoadPrivateKey(PKCS8EncodedKeySpec spec, String algorithm) {
        try {
            KeyFactory.getInstance(algorithm).generatePrivate(spec);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String signHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private static String signAsymmetric(String payload,
            String privateKeyPem,
            String algorithm) throws Exception {
        String privateKeyContent = privateKeyPem
                .replaceAll("-----BEGIN.*?-----", "")
                .replaceAll("-----END.*?-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey = KeyFactory.getInstance(KEY_ALGORITHM_ED25519.equals(algorithm) ? "Ed25519" : "RSA")
                .generatePrivate(spec);
        Signature signature = Signature.getInstance(KEY_ALGORITHM_ED25519.equals(algorithm) ? "Ed25519" : "SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static BinanceCredentialException invalidAsymmetricCredential(String credentialSource, String authMode) {
        String prefix = BinanceCredentialService.SOURCE_REQUEST_PAYLOAD.equals(credentialSource)
                ? "The asymmetric private key in the test payload"
                : "The saved asymmetric private key";
        return new BinanceCredentialException(
                LiveTradingBlockerCodes.USER_CONFIGURATION_MISMATCH,
                prefix + " is not a valid PKCS#8 RSA or Ed25519 PEM. Re-save credentials with the matching Binance private key.",
                credentialSource,
                authMode);
    }

    private static String missingPrivateMaterialMessage(String authMode) {
        if (BinanceCredentialService.AUTH_MODE_ASYMMETRIC_KEYPAIR.equals(authMode)) {
            return "Private Key PEM is required for ASYMMETRIC_KEYPAIR mode.";
        }
        return "API Secret is required for HMAC_SECRET mode.";
    }

    private static boolean looksLikePlaceholder(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("your_")
                || normalized.startsWith("your-")
                || normalized.startsWith("<")
                || normalized.contains("demo-")
                || normalized.contains("-----begin private key-----...")
                || normalized.contains("-----begin public key-----...")) {
            return true;
        }
        return PLACEHOLDER_HINTS.stream().anyMatch(normalized::contains);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
