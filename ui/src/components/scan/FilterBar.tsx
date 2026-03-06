import { Search } from 'lucide-react';

export type FilterDecision = 'ALL' | 'VALID' | 'NO_TRADE' | 'DATA_ERROR';
export type SortField = 'finalScore' | 'quoteVolume' | 'rrTp1' | 'confidence' | 'rank';

interface FilterBarProps {
    search: string;
    setSearch: (s: string) => void;
    decision: FilterDecision;
    setDecision: (d: FilterDecision) => void;
    sort: SortField;
    setSort: (s: SortField) => void;
}

export function FilterBar({ search, setSearch, decision, setDecision, sort, setSort }: FilterBarProps) {
    return (
        <div className="flex flex-col sm:flex-row gap-4 justify-between items-start sm:items-center bg-slate-800 p-3 rounded-lg border border-slate-700 shadow-sm mt-4">

            {/* Search */}
            <div className="relative w-full sm:w-64">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500" />
                <input
                    type="text"
                    placeholder="Search symbol (e.g. BTC)..."
                    value={search}
                    onChange={e => setSearch(e.target.value.toUpperCase())}
                    className="w-full bg-slate-900 border border-slate-700 rounded-md pl-9 pr-3 py-1.5 text-sm text-white focus:outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 placeholder-slate-500"
                />
            </div>

            <div className="flex flex-wrap items-center gap-4 w-full sm:w-auto">
                {/* Decision Filters */}
                <div className="flex bg-slate-900 rounded-md p-1 border border-slate-700">
                    {(['ALL', 'VALID', 'NO_TRADE'] as FilterDecision[]).map(d => (
                        <button
                            key={d}
                            onClick={() => setDecision(d)}
                            className={`px-3 py-1 text-xs font-medium rounded-sm transition-colors ${decision === d
                                ? 'bg-slate-700 text-white shadow-sm'
                                : 'text-slate-400 hover:text-slate-200'
                                }`}
                        >
                            {d.replace('_', ' ')}
                        </button>
                    ))}
                </div>

                {/* Sort Dropdown */}
                <div className="flex items-center gap-2">
                    <span className="text-xs text-slate-400">Sort by:</span>
                    <select
                        value={sort}
                        onChange={e => setSort(e.target.value as SortField)}
                        className="bg-slate-900 border border-slate-700 rounded-md py-1.5 px-3 text-sm text-white focus:outline-none focus:border-blue-500 cursor-pointer appearance-none pr-8"
                        style={{ backgroundImage: `url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' fill='none' viewBox='0 0 20 20'%3e%3cpath stroke='%2364748b' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.5' d='M6 8l4 4 4-4'/%3e%3c/svg%3e")`, backgroundPosition: 'right 0.5rem center', backgroundRepeat: 'no-repeat', backgroundSize: '1.5em 1.5em' }}
                    >
                        <option value="finalScore">Score (High-Low)</option>
                        <option value="confidence">Confidence (High-Low)</option>
                        <option value="rrTp1">RR TP1 (High-Low)</option>
                        <option value="quoteVolume">Volume 24h (High-Low)</option>
                        <option value="rank">Rank (1-300)</option>
                    </select>
                </div>
            </div>

        </div>
    );
}
