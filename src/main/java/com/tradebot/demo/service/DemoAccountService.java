package com.tradebot.demo.service;

import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.entity.DemoAccount;
import com.tradebot.demo.repository.DemoAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class DemoAccountService {

    private final DemoAccountRepository demoAccountRepository;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;

    @Transactional
    public DemoAccount getOrCreateAccount() {
        return demoAccountRepository.findFirstByOrderByCreatedAtAsc().orElseGet(() -> {
            Instant now = Instant.now();
            var startBalanceUsdt = controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().getStartBalanceUsdt();
            DemoAccount account = new DemoAccount();
            account.setCreatedAt(now);
            account.setStartingBalanceUsdt(startBalanceUsdt);
            account.setBalanceUsdt(startBalanceUsdt);
            account.setEquityUsdt(startBalanceUsdt);
            account.setModeEnabled(false);
            account.setLastUpdatedAt(now);
            return demoAccountRepository.save(account);
        });
    }

    @Transactional(readOnly = true)
    public boolean isModeEnabled() {
        return demoAccountRepository.findFirstByOrderByCreatedAtAsc()
                .map(DemoAccount::isModeEnabled)
                .orElse(false);
    }

    @Transactional
    public DemoAccount setModeEnabled(boolean enabled) {
        DemoAccount account = getOrCreateAccount();
        account.setModeEnabled(enabled);
        account.setLastUpdatedAt(Instant.now());
        return demoAccountRepository.save(account);
    }

    @Transactional
    public DemoAccount applyRealizedPnl(BigDecimal pnlUsdt) {
        DemoAccount account = getOrCreateAccount();
        BigDecimal pnl = pnlUsdt == null ? BigDecimal.ZERO : pnlUsdt;
        BigDecimal nextBalance = account.getBalanceUsdt().add(pnl);
        account.setBalanceUsdt(nextBalance);
        account.setEquityUsdt(nextBalance);
        account.setLastUpdatedAt(Instant.now());
        return demoAccountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public BigDecimal netPnl() {
        return demoAccountRepository.findFirstByOrderByCreatedAtAsc()
                .map(account -> account.getBalanceUsdt().subtract(account.getStartingBalanceUsdt()))
                .orElse(BigDecimal.ZERO);
    }
}
