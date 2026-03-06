package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_fields")
@Data
public class OrderFields {
    @Id
    private UUID recommendationId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id")
    @MapsId
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Recommendation recommendation;

    @JdbcTypeCode(SqlTypes.JSON)
    private String entryOrderJson;

    @JdbcTypeCode(SqlTypes.JSON)
    private String slOrderJson;

    @JdbcTypeCode(SqlTypes.JSON)
    private String tpOrderJson;

    private Integer leverageRecommendation;

    private String positionMode = "ONE_WAY";

    private String marginMode = "ISOLATED";

    private String workingType = "MARK_PRICE";
}
