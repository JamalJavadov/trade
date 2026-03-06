import React, { useState } from 'react';
import type { JournalItem, JournalStatus } from '../store/journalStore';
import { useNavigate } from 'react-router-dom';
import { StatusBadge } from './StatusBadge';
import { ArrowUpRight, ArrowDownRight, Search, FileText } from 'lucide-react';

interface JournalTableProps {
    items: JournalItem[];
}

export const JournalTable: React.FC<JournalTableProps> = ({ items }) => {
    const navigate = useNavigate();
    const [filterStatus, setFilterStatus] = useState<'ALL' | JournalStatus>('ALL');
    const [search, setSearch] = useState('');

    const filtered = items.filter(i => {
        if (filterStatus !== 'ALL' && i.status !== filterStatus) return false;
        if (search && !i.symbol.toLowerCase().includes(search.toLowerCase())) return false;
        return true;
    });

    if (items.length === 0) {
        return (
            <div className="bg-gray-800 rounded-lg p-12 text-center border border-gray-700">
                <FileText className="mx-auto text-gray-500 mb-4" size={48} />
                <h3 className="text-xl font-medium text-gray-300">Journal is empty</h3>
                <p className="text-gray-500 mt-2">New incoming recommendations will appear here automatically.</p>
            </div>
        );
    }

    return (
        <div className="bg-gray-800 rounded-lg shadow-xl border border-gray-700 overflow-hidden">

            {/* Filters */}
            <div className="p-4 border-b border-gray-700 flex flex-col sm:flex-row justify-between items-center gap-4">
                <div className="relative w-full sm:w-64">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" size={16} />
                    <input
                        type="text"
                        placeholder="Search symbols..."
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        className="w-full bg-gray-900 border border-gray-600 text-gray-200 rounded py-2 pl-9 pr-4 focus:ring-2 focus:ring-blue-500 outline-none"
                    />
                </div>
                <div className="flex space-x-2 w-full sm:w-auto overflow-x-auto">
                    {['ALL', 'NEW', 'OPEN', 'CLOSED'].map(f => (
                        <button
                            key={f}
                            onClick={() => setFilterStatus(f as any)}
                            className={`px-3 py-1 text-sm font-medium rounded-full transition-colors ${filterStatus === f ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
                                }`}
                        >
                            {f}
                        </button>
                    ))}
                </div>
            </div>

            <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse">
                    <thead>
                        <tr className="bg-gray-900 border-b border-gray-700 text-gray-400 text-sm uppercase tracking-wider">
                            <th className="p-4 font-semibold">Date</th>
                            <th className="p-4 font-semibold">Symbol</th>
                            <th className="p-4 font-semibold">Setup</th>
                            <th className="p-4 font-semibold">R/R</th>
                            <th className="p-4 font-semibold text-center">Result</th>
                            <th className="p-4 font-semibold text-center">Status</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-700">
                        {filtered.map(item => (
                            <tr
                                key={item.id}
                                onClick={() => navigate(`/recommendation/${item.id}`)}
                                className="hover:bg-gray-750 cursor-pointer transition-colors bg-gray-800 hover:bg-gray-700"
                            >
                                <td className="p-4 whitespace-nowrap text-gray-300 text-sm">
                                    {new Date(item.createdAt).toLocaleString()}
                                </td>
                                <td className="p-4 font-bold text-white tracking-widest">
                                    {item.symbol}
                                </td>
                                <td className="p-4">
                                    <span className={`inline-flex items-center text-xs font-bold px-2 py-1 rounded-full ${item.side === 'BUY' ? 'text-green-400 bg-green-900/40' : 'text-red-400 bg-red-900/40'}`}>
                                        {item.side === 'BUY' ? <ArrowUpRight size={14} className="mr-1" /> : <ArrowDownRight size={14} className="mr-1" />}
                                        {item.side}
                                    </span>
                                </td>
                                <td className="p-4 font-mono text-yellow-400">
                                    {item.rrToTp1.toFixed(2)}R
                                </td>
                                <td className="p-4 text-center">
                                    {item.feedbackResult ? (
                                        <span className={`font-bold ${item.feedbackResult === 'WIN' ? 'text-green-500' : 'text-red-500'}`}>
                                            {item.feedbackResult}
                                            {item.feedbackR ? ` (${item.feedbackR > 0 ? '+' : ''}${item.feedbackR}R)` : ''}
                                        </span>
                                    ) : (
                                        <span className="text-gray-600">-</span>
                                    )}
                                </td>
                                <td className="p-4 flex justify-center">
                                    <StatusBadge status={item.status} />
                                </td>
                            </tr>
                        ))}
                        {filtered.length === 0 && (
                            <tr>
                                <td colSpan={6} className="p-8 text-center text-gray-500">
                                    No matching journal entries found.
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};
