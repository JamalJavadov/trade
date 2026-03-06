package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "ai_suggestion_item")
@IdClass(AiSuggestionItemId.class)
@Data
public class AiSuggestionItem {
    @Id
    @Column(name = "batch_id")
    private UUID batchId;

    @Id
    @Column(name = "key")
    private String key;

    private String proposedValue;

    private String reason;

    private String impactHypothesis;

    private String riskOfChange;

    private String status;
}
