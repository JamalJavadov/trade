package com.tradebot.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.UUID;

@Data
public class AiSuggestionItemId implements Serializable {
    private UUID batchId;
    private String key;
}
