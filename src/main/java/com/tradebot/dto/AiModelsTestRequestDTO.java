package com.tradebot.dto;

import com.tradebot.ai.AiTaskType;
import lombok.Data;

@Data
public class AiModelsTestRequestDTO {
    private AiTaskType taskType = AiTaskType.SUGGESTION_BATCH;
}
