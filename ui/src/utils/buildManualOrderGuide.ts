import type { RecommendationDTO } from '../api/client';
import { formatPrice, formatQty, calcNotional } from './format';

export interface ManualOrderGuide {
    quickSummary: string;
    marginMode: string;
    leverage: string;
    side: string;
    actionButton: string;
    orderTab: string;
    quantityCoin: string;
    quantitySymbol: string;
    notionalUsdt: string | null;
    tpslEnabled: boolean;
    tpTrigger: string;
    tpPrice: string;
    slTrigger: string;
    slPrice: string;
    reduceOnlyEntry: boolean;
    hasAdvanced: boolean;
    checklist: string[];
    separateExitGuide: string[];
}

function baseAsset(symbol: string): string {
    if (symbol.endsWith('USDT')) return symbol.slice(0, -4);
    if (symbol.endsWith('BUSD')) return symbol.slice(0, -4);
    return symbol;
}

function leverageLabel(lev: number | null | undefined): string {
    if (!lev) return '5x';
    return `${lev}x`;
}

export function buildManualOrderGuide(rec: RecommendationDTO): ManualOrderGuide {
    const isBuy = rec.side === 'BUY';
    const side = isBuy ? 'LONG' : 'SHORT';
    const actionButton = isBuy ? 'BUY / LONG' : 'SELL / SHORT';
    const leverage = leverageLabel(rec.leverageRecommendation);
    const marginMode = rec.marginMode ?? 'ISOLATED';
    const sym = rec.symbol;
    const asset = baseAsset(sym);

    const qty = rec.entryOrder?.quantity;
    const entryPrice = rec.entryOrder?.price;
    const slStopPrice = rec.slOrder?.stopPrice;
    const tpStopPrice = rec.tpOrder?.stopPrice;
    const qtyFormatted = formatQty(qty);
    const slPriceFormatted = formatPrice(slStopPrice);
    const tpPriceFormatted = formatPrice(tpStopPrice);
    const notionalUsdt = calcNotional(qty, entryPrice);
    const tpTrigger = 'MARK';
    const slTrigger = 'MARK';

    const quickSummary = `${sym} — ${side} — ${marginMode} — ${leverage} — Size: ${qtyFormatted} ${asset} — SL: ${slPriceFormatted} — TP: ${tpPriceFormatted} (${tpTrigger})`;

    const checklist = [
        `Confirm symbol is set to ${sym}`,
        `Confirm margin mode is ${marginMode} and leverage is ${leverage}`,
        `Confirm Size is ${qtyFormatted} ${asset}${notionalUsdt ? ` (~${notionalUsdt} USDT)` : ''}`,
        `Action Button: ${actionButton}`,
        'TP / SL Toggle: ON',
        'Take Profit Trigger: MARK',
        'Stop Loss Trigger: MARK',
        `Take Profit Price: ${tpPriceFormatted}`,
        `Stop Loss Price: ${slPriceFormatted}`,
        isBuy ? 'LONG rule in entry panel: TP > MARK > SL' : 'SHORT rule in entry panel: SL > MARK > TP',
        `Confirm SL distance is within your approved risk budget`,
    ];

    const separateExitGuide = isBuy
        ? [
            'LONG exit side: SELL',
            'Set reduceOnly=true and closePosition=true',
            'SL type: STOP_MARKET',
            'TP type: TAKE_PROFIT_MARKET'
        ]
        : [
            'SHORT exit side: BUY',
            'Set reduceOnly=true and closePosition=true',
            'SL type: STOP_MARKET',
            'TP type: TAKE_PROFIT_MARKET'
        ];

    return {
        quickSummary,
        marginMode,
        leverage,
        side,
        actionButton,
        orderTab: 'MARKET',
        quantityCoin: qtyFormatted,
        quantitySymbol: asset,
        notionalUsdt,
        tpslEnabled: true,
        tpTrigger,
        tpPrice: tpPriceFormatted,
        slTrigger,
        slPrice: slPriceFormatted,
        reduceOnlyEntry: false,
        hasAdvanced: true,
        checklist,
        separateExitGuide,
    };
}
