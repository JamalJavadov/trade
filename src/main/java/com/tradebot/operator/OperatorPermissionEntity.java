package com.tradebot.operator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "operator_permission")
@Data
public class OperatorPermissionEntity {

    @Id
    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "danger_level", nullable = false)
    private String dangerLevel;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
