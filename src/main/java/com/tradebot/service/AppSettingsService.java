package com.tradebot.service;

import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.SettingsUpdateRequestDTO;
import com.tradebot.entity.AppSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppSettingsService {

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;

    @Transactional(readOnly = true)
    public AppSettings getSettings() {
        return controlCenterSettingsProvider.getLegacySettings();
    }

    @Transactional
    public AppSettings updateSettings(SettingsUpdateRequestDTO dto) {
        return controlCenterSettingsProvider.updateLegacySettings(dto);
    }
}
