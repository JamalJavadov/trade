package com.tradebot.dto;

import com.tradebot.ai.AiTaskType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiModelTaskUpdateDTO {
    private AiTaskType taskType;
    private String primaryModel;
    private List<String> fallbackModels = new ArrayList<>();
}
