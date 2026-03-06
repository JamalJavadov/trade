package com.tradebot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiModelsUpdateRequestDTO {
    private boolean revertToDefaults;
    private List<AiModelTaskUpdateDTO> tasks = new ArrayList<>();
}
