package com.tradebot.dto;

import lombok.Data;

@Data
public class BinanceCredentialRequestDTO {
    private String apiKey;

    private String publicKeyPem;
    private String privateKeyPem;

    private String authMode;
}
