package com.tradebot.dto;

import lombok.Data;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Data
public class ScanChartsDTO {

    private List<HistBin> rrHist;
    private List<HistBin> confHist;
    private List<ScatterPoint> scatterPoints;
    private Map<String, Integer> skipReasonBreakdown;
    private Map<String, Integer> biasBreakdown;

    @Data
    public static class HistBin {
        private Double bucketStart;
        private Double bucketEnd;
        private String binLabel;
        private int count;

        public HistBin(double bucketStart, double bucketEnd, int count) {
            this.bucketStart = bucketStart;
            this.bucketEnd = bucketEnd;
            this.binLabel = String.format(Locale.US, "%.2f-%.2f", bucketStart, bucketEnd);
            this.count = count;
        }
    }

    @Data
    public static class ScatterPoint {
        private String symbol;
        private Double rrTp1;
        private Double confidence;
        private Double finalScore;

        public ScatterPoint(String symbol, Double rrTp1, Double confidence, Double finalScore) {
            this.symbol = symbol;
            this.rrTp1 = rrTp1;
            this.confidence = confidence;
            this.finalScore = finalScore;
        }
    }
}
