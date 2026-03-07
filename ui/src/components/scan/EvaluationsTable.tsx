import { useRef } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { SymbolEvaluationRowDTO } from '../../types/scan';

export function EvaluationsTable({
    rows,
    onRowClick
}: {
    rows: SymbolEvaluationRowDTO[];
    onRowClick: (symbol: string) => void;
}) {
    const parentRef = useRef<HTMLDivElement>(null);

    const rowVirtualizer = useVirtualizer({
        count: rows.length,
        getScrollElement: () => parentRef.current,
        estimateSize: () => 48, // 48px row height
        overscan: 10,
    });

    return (
        <div className="bg-slate-800 border border-slate-700 rounded-xl shadow-lg mt-4 overflow-hidden flex flex-col h-[500px]">
            {/* Table Header */}
            <div className="grid grid-cols-14 gap-2 px-4 py-3 bg-slate-900/50 border-b border-slate-700 text-xs font-semibold text-slate-400 uppercase tracking-wider sticky top-0 z-10">
                <div className="col-span-1 text-center">Rank</div>
                <div className="col-span-2">Symbol</div>
                <div className="col-span-2 text-center">Decision</div>
                <div className="col-span-1 text-center">Gate</div>
                <div className="col-span-1 text-center">Side</div>
                <div className="col-span-1 text-right">RR</div>
                <div className="col-span-2 text-right">Score / Integrity</div>
                <div className="col-span-2 text-right">AI / Conflict</div>
                <div className="col-span-2 text-right text-slate-500">Reason</div>
            </div>

            {/* Virtualized Body */}
            <div
                ref={parentRef}
                className="flex-1 overflow-auto bg-slate-800 custom-scrollbar"
                style={{ height: '100%', width: '100%' }}
            >
                <div
                    style={{
                        height: `${rowVirtualizer.getTotalSize()}px`,
                        width: '100%',
                        position: 'relative',
                    }}
                >
                    {rows.length === 0 && (
                        <div className="absolute inset-0 flex flex-col items-center justify-center text-slate-500 text-sm">
                            No symbols evaluated yet or matched filters.
                        </div>
                    )}
                    {rowVirtualizer.getVirtualItems().map((virtualRow) => {
                        const row = rows[virtualRow.index];
                        if (!row) return null;
                        const isValid = row.decision === 'VALID';
                        const isError = row.decision === 'DATA_ERROR';
                        const gateEligible = row.recommendationEligible === true;
                        const reasonText = row.skipReasonCode || row.rejectionReasons?.[0] || row.conflictState || '-';

                        return (
                            <div
                                key={virtualRow.key}
                                onClick={() => onRowClick(row.symbol)}
                                className={`
                                    absolute top-0 left-0 w-full grid grid-cols-14 gap-2 px-4 items-center 
                                    border-b border-slate-700/50 text-sm text-slate-300 hover:bg-slate-700/50 cursor-pointer transition-colors
                                `}
                                style={{
                                    height: `${virtualRow.size}px`,
                                    transform: `translateY(${virtualRow.start}px)`,
                                }}
                            >
                                <div className="col-span-1 text-center font-mono text-xs text-slate-500">
                                    #{row.rankInUniverse ?? '-'}
                                </div>
                                <div className="col-span-2 font-medium text-white flex items-center gap-1">
                                    {row.symbol}
                                </div>
                                <div className="col-span-2 text-center">
                                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold tracking-wider ${isValid ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20' :
                                        isError ? 'bg-rose-500/10 text-rose-400 border border-rose-500/20' :
                                            'bg-slate-700 text-slate-400 border border-slate-600'
                                        }`}>
                                        {row.decision}
                                    </span>
                                </div>
                                <div className="col-span-1 text-center">
                                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold tracking-wider ${gateEligible
                                        ? 'bg-blue-500/10 text-blue-300 border border-blue-500/20'
                                        : row.decision === 'VALID'
                                            ? 'bg-amber-500/10 text-amber-300 border border-amber-500/20'
                                            : 'bg-slate-700 text-slate-400 border border-slate-600'
                                        }`}>
                                        {gateEligible ? 'READY' : row.decision === 'VALID' ? 'BLOCK' : 'N/A'}
                                    </span>
                                </div>
                                <div className="col-span-1 text-center">
                                    {row.side !== 'NONE' && (
                                        <span className={`text-xs font-bold ${row.side === 'LONG' ? 'text-emerald-400' : 'text-rose-400'}`}>
                                            {row.side}
                                        </span>
                                    )}
                                </div>
                                <div className="col-span-1 text-right font-mono">
                                    {row.rrTp1 != null ? (
                                        <span className={row.rrTp1 >= 2.5 ? 'text-blue-400 font-medium' : 'text-slate-400'}>
                                            {Number(row.rrTp1).toFixed(2)}R
                                        </span>
                                    ) : '-'}
                                </div>
                                <div className="col-span-2 text-right font-mono text-xs">
                                    {row.finalScore != null ? (
                                        <div className="flex flex-col items-end leading-tight">
                                            <span className="text-white">{Number(row.finalScore).toFixed(2)}</span>
                                            <span className="text-slate-500">I:{row.finalIntegrityScore ?? '-'}</span>
                                        </div>
                                    ) : '-'}
                                </div>
                                <div className="col-span-2 text-right text-xs px-1">
                                    <div className="truncate text-slate-300" title={row.aiAgreementState || ''}>
                                        {row.aiAgreementState || row.aiReviewStatus || '-'}
                                    </div>
                                    <div className="truncate text-slate-500" title={row.conflictState || ''}>
                                        {row.conflictState || 'NONE'}
                                    </div>
                                </div>
                                <div className="col-span-2 text-right text-xs truncate text-slate-500 px-1" title={row.skipReasonText || row.rejectionReasons?.join(', ') || ''}>
                                    {reasonText}
                                </div>
                            </div>
                        );
                    })}
                </div>
            </div>

            <style dangerouslySetInnerHTML={{
                __html: `
                .custom-scrollbar::-webkit-scrollbar { width: 6px; }
                .custom-scrollbar::-webkit-scrollbar-track { background: transparent; }
                .custom-scrollbar::-webkit-scrollbar-thumb { background-color: #334155; border-radius: 20px; }
            `}} />
        </div>
    );
}
