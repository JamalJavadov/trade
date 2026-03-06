package com.tradebot.operator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorPermissionCatalogSyncService implements ApplicationRunner {

    private final PermissionCatalog permissionCatalog;
    private final OperatorPermissionRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        try {
            syncCatalog();
        } catch (Exception ex) {
            log.warn("Operator permission catalog sync skipped: {}", ex.getMessage(), ex);
        }
    }

    public void syncCatalog() {
        List<OperatorPermissionEntity> allRows = repository.findAll();
        Map<String, OperatorPermissionEntity> existingByKey = new HashMap<>();
        for (OperatorPermissionEntity row : allRows) {
            existingByKey.put(row.getKey(), row);
        }

        List<OperatorPermissionEntity> toInsert = new ArrayList<>();
        int inserted = 0;

        for (PermissionCatalog.PermissionDefinition definition : permissionCatalog.all()) {
            OperatorPermissionEntity existing = existingByKey.get(definition.permissionKey());
            if (existing != null) {
                continue;
            }

            OperatorPermissionEntity created = new OperatorPermissionEntity();
            created.setKey(definition.permissionKey());
            created.setEnabled(true);
            created.setTitle(definition.title());
            created.setDescription(definition.description());
            created.setGroupName(definition.group().name());
            created.setDangerLevel(definition.dangerLevel().name());
            toInsert.add(created);
            inserted++;
        }

        if (!toInsert.isEmpty()) {
            repository.saveAll(toInsert);
        }

        int unchanged = permissionCatalog.all().size() - inserted;
        log.info("Operator permissions bootstrap complete: insertedMissing={}, existing={}", inserted, unchanged);
    }
}
