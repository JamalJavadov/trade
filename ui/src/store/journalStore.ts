export type JournalStatus = 'NEW' | 'OPEN' | 'CLOSED';

export interface JournalItem {
    id: string;
    createdAt: string;
    symbol: string;
    side: string;
    entry: string;
    sl: string;
    tp1: string;
    rrToTp1: number;
    confidence: number;
    status: JournalStatus;

    // Feedback summary if closed
    feedbackResult?: 'WIN' | 'LOSS';
    feedbackPnl?: number;
    feedbackR?: number;
}

const STORAGE_KEY = 'tradebot_journal';
const MAX_ITEMS = 200;

class JournalStore {

    list(): JournalItem[] {
        try {
            const data = localStorage.getItem(STORAGE_KEY);
            if (!data) return [];
            return JSON.parse(data);
        } catch {
            return [];
        }
    }

    save(items: JournalItem[]) {
        // sort newest first and trim
        const trim = items
            .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
            .slice(0, MAX_ITEMS);
        localStorage.setItem(STORAGE_KEY, JSON.stringify(trim));
    }

    upsertFromLatest(rec: any) {
        if (!rec || !rec.id) return;

        const items = this.list();
        const existingIdx = items.findIndex(i => i.id === rec.id);

        if (existingIdx !== -1) {
            // already in journal, do not overwrite user-feedback status if already closed
            return;
        }

        const newItem: JournalItem = {
            id: rec.id,
            createdAt: rec.createdAt,
            symbol: rec.symbol,
            side: rec.side,
            entry: rec.entryOrder?.stopPrice || "MARKET",
            sl: rec.slOrder?.stopPrice || "",
            tp1: rec.tpOrder?.stopPrice || "",
            rrToTp1: rec.confidenceScore || 0,
            confidence: rec.confidenceScore || 0,
            status: 'NEW'
        };

        items.unshift(newItem);
        this.save(items);
    }

    markAsOpen(id: string) {
        const items = this.list();
        const idx = items.findIndex(i => i.id === id);
        if (idx !== -1 && items[idx].status === 'NEW') {
            items[idx].status = 'OPEN';
            this.save(items);
        }
    }

    updateFeedbackStatus(id: string, result: 'WIN' | 'LOSS', pnl?: number, rMultiple?: number) {
        const items = this.list();
        const idx = items.findIndex(i => i.id === id);
        if (idx !== -1) {
            items[idx].status = 'CLOSED';
            items[idx].feedbackResult = result;
            items[idx].feedbackPnl = pnl;
            items[idx].feedbackR = rMultiple;
            this.save(items);
        }
    }
}

export const journalStore = new JournalStore();
