package com.tradebot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdvancedRecommendationDTO extends RecommendationDTO {
    private String triggerRationale;
    private String partialPlanDescription;
}
