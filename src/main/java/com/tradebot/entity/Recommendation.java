package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recommendation")
@Data
public class Recommendation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_run_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ScanRun scanRun;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    private String rationaleText;

    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private String status;

    @OneToOne(mappedBy = "recommendation", cascade = CascadeType.ALL)
    private OrderFields orderFields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diagnostics_json", columnDefinition = "jsonb")
    private String diagnosticsJson;
}
