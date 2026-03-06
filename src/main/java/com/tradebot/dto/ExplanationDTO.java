package com.tradebot.dto;

import lombok.Data;
import java.util.List;

@Data
public class ExplanationDTO {
    private String headline;
    private List<String> bullets;
    private List<String> tags;
}
