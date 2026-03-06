package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "strategy_config_version")
@Data
public class StrategyConfigVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private Integer version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Boolean active = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String configJson;

    private String changeReason;
}
