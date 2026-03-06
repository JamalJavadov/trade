package com.tradebot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDiagnosticsRequestDTO {

    @NotBlank(message = "title is required")
    @Size(max = 200, message = "title must be at most 200 characters")
    private String title;

    @NotBlank(message = "contextText is required")
    @Size(max = 8000, message = "contextText must be at most 8000 characters")
    private String contextText;

    @Size(max = 12000, message = "logs must be at most 12000 characters")
    private String logs;

    private Map<String, Object> constraints = new LinkedHashMap<>();
}
