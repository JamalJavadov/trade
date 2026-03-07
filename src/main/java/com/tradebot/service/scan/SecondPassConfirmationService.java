package com.tradebot.service.scan;

import com.tradebot.config.AppProperties;
import com.tradebot.dto.StrategyTuningConfig;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SecondPassConfirmationService {

    public ConfirmationResult evaluate(
            FrozenSymbolSnapshot snapshot,
            AppProperties runtimeProperties,
            StrategyTuningConfig tuningConfig,
            DeterministicStrategyResult firstPass,
            DeterministicStrategyEvaluator evaluator) {
        Instant startedAt = Instant.now();
        DeterministicStrategyResult secondPass = evaluator.evaluate(snapshot, runtimeProperties, tuningConfig);
        List<String> disagreements = new ArrayList<>();
        Map<String, Object> comparedFields = new LinkedHashMap<>();

        compare("decision", firstPass.decision(), secondPass.decision(), disagreements, comparedFields);
        compare("side", firstPass.side(), secondPass.side(), disagreements, comparedFields);
        compare("bias", firstPass.bias(), secondPass.bias(), disagreements, comparedFields);
        compare("skipReasonCode", firstPass.skipReasonCode(), secondPass.skipReasonCode(), disagreements, comparedFields);
        compare("metrics", firstPass.metrics(), secondPass.metrics(), disagreements, comparedFields);
        compare("diagnostics", firstPass.diagnostics(), secondPass.diagnostics(), disagreements, comparedFields);

        boolean blocking = !Objects.equals(firstPass.decision(), secondPass.decision())
                || !Objects.equals(firstPass.side(), secondPass.side())
                || !Objects.equals(firstPass.bias(), secondPass.bias())
                || !Objects.equals(firstPass.skipReasonCode(), secondPass.skipReasonCode());

        ConflictReport report;
        String status;
        if (disagreements.isEmpty()) {
            report = ConflictReport.none();
            status = "PASSED";
        } else {
            report = new ConflictReport(
                    blocking ? "FINAL_DECISION_MISMATCH" : "METRIC_MISMATCH",
                    blocking,
                    List.copyOf(disagreements),
                    immutable(comparedFields),
                    blocking
                            ? "Independent confirmation changed deterministic eligibility or state."
                            : "Independent confirmation preserved eligibility but changed deterministic evidence.");
            status = blocking ? "FAILED" : "FRAGILE";
        }

        StageAudit audit = new StageAudit(
                DeepScanStage.SECOND_PASS_CONFIRMATION,
                status,
                disagreements.isEmpty()
                        ? List.of(new StageFinding("SECOND_PASS_MATCH", "INFO",
                                "Independent second-pass confirmation matched the first deterministic evaluation.", Map.of()))
                        : List.of(new StageFinding(blocking ? "SECOND_PASS_CONFLICT" : "SECOND_PASS_DRIFT",
                                blocking ? "CRITICAL" : "WARN",
                                report.summary(),
                                detail("disagreements", disagreements))),
                detail("comparedFields", immutable(comparedFields), "blocking", blocking),
                startedAt,
                Instant.now());
        return new ConfirmationResult(audit, report);
    }

    private void compare(
            String field,
            Object first,
            Object second,
            List<String> disagreements,
            Map<String, Object> comparedFields) {
        if (!Objects.equals(first, second)) {
            disagreements.add(field);
            comparedFields.put(field, detail("first", first, "second", second));
        }
    }

    public record ConfirmationResult(StageAudit audit, ConflictReport report) {
    }

    private Map<String, Object> detail(Object... entries) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            detail.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return Collections.unmodifiableMap(detail);
    }

    private Map<String, Object> immutable(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
