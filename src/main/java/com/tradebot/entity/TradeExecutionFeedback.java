package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "trade_execution_feedback")
@Data
public class TradeExecutionFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Recommendation recommendation;

    @Column(nullable = false)
    private String userLabel;

    @Column(precision = 20, scale = 8)
    private BigDecimal pnlUsdt;

    @Column(precision = 10, scale = 4)
    private BigDecimal rMultiple;

    private String notes;

    private Instant closedAt;

    @Column(nullable = false)
    private Instant createdAt;
}
