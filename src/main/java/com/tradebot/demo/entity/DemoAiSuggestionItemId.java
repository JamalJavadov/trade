package com.tradebot.demo.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

@Data
public class DemoAiSuggestionItemId implements Serializable {
    private UUID batchId;
    private String key;
}
