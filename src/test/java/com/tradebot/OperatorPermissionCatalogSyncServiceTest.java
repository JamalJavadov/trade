package com.tradebot;

import com.tradebot.operator.OperatorPermissionCatalogSyncService;
import com.tradebot.operator.OperatorPermissionEntity;
import com.tradebot.operator.OperatorPermissionRepository;
import com.tradebot.operator.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperatorPermissionCatalogSyncServiceTest {

    @Test
    void syncCatalogInsertsMissingKeysWithEnabledTrue() {
        PermissionCatalog catalog = new PermissionCatalog();
        OperatorPermissionRepository repository = mock(OperatorPermissionRepository.class);
        when(repository.findAll()).thenReturn(List.of());

        OperatorPermissionCatalogSyncService service = new OperatorPermissionCatalogSyncService(catalog, repository);
        service.syncCatalog();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OperatorPermissionEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());

        List<OperatorPermissionEntity> inserted = captor.getValue();
        assertEquals(catalog.all().size(), inserted.size());
        assertTrue(inserted.stream().allMatch(OperatorPermissionEntity::isEnabled));

        Set<String> expectedKeys = catalog.all().stream()
                .map(PermissionCatalog.PermissionDefinition::permissionKey)
                .collect(Collectors.toSet());
        Set<String> actualKeys = inserted.stream()
                .map(OperatorPermissionEntity::getKey)
                .collect(Collectors.toSet());

        assertEquals(expectedKeys, actualKeys);
    }

    @Test
    void syncCatalogDoesNotOverwriteExistingRows() {
        PermissionCatalog catalog = new PermissionCatalog();
        List<OperatorPermissionEntity> existingRows = catalog.all().stream().map(definition -> {
            OperatorPermissionEntity row = new OperatorPermissionEntity();
            row.setKey(definition.permissionKey());
            row.setEnabled(false);
            row.setTitle("old-title");
            row.setDescription("old-description");
            row.setGroupName("OLD");
            row.setDangerLevel("LOW");
            return row;
        }).toList();

        OperatorPermissionRepository repository = mock(OperatorPermissionRepository.class);
        when(repository.findAll()).thenReturn(existingRows);

        OperatorPermissionCatalogSyncService service = new OperatorPermissionCatalogSyncService(catalog, repository);
        service.syncCatalog();

        verify(repository, never()).saveAll(any());
    }
}
