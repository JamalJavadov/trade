package com.tradebot;

import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.SettingsUpdateRequestDTO;
import com.tradebot.entity.AppSettings;
import com.tradebot.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppSettingsServiceTest {

    private ControlCenterSettingsProvider provider;
    private AppSettingsService service;

    @BeforeEach
    void setUp() {
        provider = mock(ControlCenterSettingsProvider.class);
        service = new AppSettingsService(provider);
    }

    @Test
    void getSettingsDelegatesToControlCenterProvider() {
        AppSettings expected = new AppSettings();
        when(provider.getLegacySettings()).thenReturn(expected);

        AppSettings actual = service.getSettings();

        assertSame(expected, actual);
        verify(provider).getLegacySettings();
    }

    @Test
    void updateSettingsDelegatesToControlCenterProvider() {
        SettingsUpdateRequestDTO request = new SettingsUpdateRequestDTO();
        AppSettings expected = new AppSettings();
        when(provider.updateLegacySettings(request)).thenReturn(expected);

        AppSettings actual = service.updateSettings(request);

        assertSame(expected, actual);
        verify(provider).updateLegacySettings(request);
    }
}
