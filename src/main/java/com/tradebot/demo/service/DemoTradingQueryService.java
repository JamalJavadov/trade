package com.tradebot.demo.service;

import com.tradebot.demo.dto.DemoTradeDetailDTO;
import com.tradebot.demo.dto.DemoTradeListResponseDTO;
import com.tradebot.demo.dto.DemoTradeSummaryDTO;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.repository.DemoTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DemoTradingQueryService {

    private final DemoTradeRepository demoTradeRepository;

    @Transactional(readOnly = true)
    public DemoTradeListResponseDTO getTrades(int limit, int offset) {
        int safeLimit = Math.max(limit, 1);
        int safeOffset = Math.max(offset, 0);
        int page = safeOffset / safeLimit;

        Page<DemoTrade> result = demoTradeRepository.findAllByOrderByOpenedAtDesc(PageRequest.of(page, safeLimit));

        DemoTradeListResponseDTO dto = new DemoTradeListResponseDTO();
        dto.setLimit(safeLimit);
        dto.setOffset(safeOffset);
        dto.setTotal(result.getTotalElements());
        dto.setTrades(result.getContent().stream().map(this::toSummary).toList());
        return dto;
    }

    @Transactional(readOnly = true)
    public DemoTradeDetailDTO getTradeById(UUID id) {
        DemoTrade trade = demoTradeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Demo trade not found: " + id));
        return toDetail(trade);
    }

    @Transactional(readOnly = true)
    public DemoTradeListResponseDTO getOpenTrades() {
        var open = demoTradeRepository.findByStatusOrderByOpenedAtDesc("OPEN");
        DemoTradeListResponseDTO dto = new DemoTradeListResponseDTO();
        dto.setLimit(open.size());
        dto.setOffset(0);
        dto.setTotal(open.size());
        dto.setTrades(open.stream().map(this::toSummary).toList());
        return dto;
    }

    public DemoTradeSummaryDTO toSummary(DemoTrade trade) {
        DemoTradeSummaryDTO dto = new DemoTradeSummaryDTO();
        dto.setId(trade.getId());
        dto.setOpenedAt(trade.getOpenedAt());
        dto.setClosedAt(trade.getClosedAt());
        dto.setSymbol(trade.getSymbol());
        dto.setSide(trade.getSide());
        dto.setStatus(trade.getStatus());
        dto.setCloseReason(trade.getCloseReason());
        dto.setStage(trade.getStage());
        dto.setRemainingQty(trade.getRemainingQty());
        dto.setPnlUsdt(trade.getPnlUsdt());
        dto.setRMultiple(trade.getRMultiple());
        return dto;
    }

    private DemoTradeDetailDTO toDetail(DemoTrade trade) {
        DemoTradeDetailDTO dto = new DemoTradeDetailDTO();
        dto.setId(trade.getId());
        dto.setCreatedAt(trade.getCreatedAt());
        dto.setOpenedAt(trade.getOpenedAt());
        dto.setClosedAt(trade.getClosedAt());
        dto.setSymbol(trade.getSymbol());
        dto.setSide(trade.getSide());
        dto.setLeverage(trade.getLeverage());
        dto.setQty(trade.getQty());
        dto.setRemainingQty(trade.getRemainingQty());
        dto.setEntryPrice(trade.getEntryPrice());
        dto.setSlPrice(trade.getSlPrice());
        dto.setCurrentSlPrice(trade.getCurrentSlPrice());
        dto.setTp1Price(trade.getTp1Price());
        dto.setTp2Price(trade.getTp2Price());
        dto.setTp3Price(trade.getTp3Price());
        dto.setWorkingType(trade.getWorkingType());
        dto.setStatus(trade.getStatus());
        dto.setCloseReason(trade.getCloseReason());
        dto.setStage(trade.getStage());
        dto.setRiskUsdtInitial(trade.getRiskUsdtInitial());
        dto.setRealizedPnlUsdt(trade.getRealizedPnlUsdt());
        dto.setEntryFeeUsdt(trade.getEntryFeeUsdt());
        dto.setExitFeeUsdt(trade.getExitFeeUsdt());
        dto.setTotalFeesUsdt(trade.getTotalFeesUsdt());
        dto.setLastMarkPrice(trade.getLastMarkPrice());
        dto.setPnlUsdt(trade.getPnlUsdt());
        dto.setRMultiple(trade.getRMultiple());
        dto.setSnapshotJson(trade.getSnapshotJson());
        return dto;
    }
}
