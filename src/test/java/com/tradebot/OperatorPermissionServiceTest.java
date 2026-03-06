package com.tradebot;

import com.tradebot.operator.OperatorPermissionEntity;
import com.tradebot.operator.OperatorPermissionRepository;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.operator.OperatorPermissionUpdateDTO;
import com.tradebot.operator.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperatorPermissionServiceTest {

    @Test
    void updatePermissionsWithUnknownKeyThrowsBadRequest() {
        OperatorPermissionRepository repository = mock(OperatorPermissionRepository.class);
        PermissionCatalog catalog = new PermissionCatalog();
        OperatorPermissionService service = new OperatorPermissionService(repository, catalog);

        when(repository.existsById("unknown.permission")).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updatePermissions(List.of(new OperatorPermissionUpdateDTO("unknown.permission", true)),
                        "operator-a"));

        assertEquals("Unknown permission key: unknown.permission", ex.getMessage());
    }

    @Test
    void updatePermissionsSetsUpdatedByAndTimestamp() {
        OperatorPermissionRepository repository = mock(OperatorPermissionRepository.class);
        PermissionCatalog catalog = new PermissionCatalog();
        OperatorPermissionService service = new OperatorPermissionService(repository, catalog);

        OperatorPermissionEntity existing = new OperatorPermissionEntity();
        existing.setKey("scan.run_once");
        existing.setEnabled(true);
        existing.setTitle("Run Scan Once");
        existing.setDescription("desc");
        existing.setGroupName("SCAN");
        existing.setDangerLevel("MED");

        when(repository.existsById("scan.run_once")).thenReturn(true);
        when(repository.findById("scan.run_once")).thenReturn(Optional.of(existing));
        when(repository.findAllByOrderByGroupNameAscKeyAsc()).thenReturn(List.of(existing));

        service.updatePermissions(List.of(new OperatorPermissionUpdateDTO("scan.run_once", false)), "alice");

        ArgumentCaptor<OperatorPermissionEntity> captor = ArgumentCaptor.forClass(OperatorPermissionEntity.class);
        verify(repository, times(1)).save(captor.capture());
        OperatorPermissionEntity saved = captor.getValue();

        assertEquals(false, saved.isEnabled());
        assertEquals("alice", saved.getUpdatedBy());
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getUpdatedAt());

        verify(repository, times(1)).findAllByOrderByGroupNameAscKeyAsc();
        verify(repository, times(1)).findById("scan.run_once");
        verify(repository, times(0)).existsById("scan.run_once");
        verify(repository, times(0)).saveAll(any());
    }
}
