package com.tradebot.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.UUID;

@Data
public class SymbolUniverseSnapshotId implements Serializable {
    private UUID scanRunId;
    private String symbol;
}
