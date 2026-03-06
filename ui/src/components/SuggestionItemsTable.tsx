import React from 'react';
import type { AiSuggestionItemDTO } from '../api/client';


interface SuggestionItemsTableProps {
    items: AiSuggestionItemDTO[];
}

export const SuggestionItemsTable: React.FC<SuggestionItemsTableProps> = ({ items }) => {
    return (
        <div className="overflow-x-auto mt-6 bg-gray-900 rounded border border-gray-700">
            <table className="w-full text-left text-sm">
                <thead className="bg-gray-950 text-gray-400 border-b border-gray-700">
                    <tr>
                        <th className="p-3 font-semibold w-1/5">Parameter Key</th>
                        <th className="p-3 font-semibold w-1/5">Proposed Value</th>
                        <th className="p-3 font-semibold w-2/5">Reasoning & Impact</th>
                        <th className="p-3 font-semibold w-1/5 text-center">Risk Level</th>
                    </tr>
                </thead>
                <tbody className="divide-y divide-gray-800">
                    {items.map(item => (
                        <tr key={item.id} className="hover:bg-gray-800/50 transition-colors">
                            <td className="p-3 font-mono text-blue-300 font-bold max-w-xs truncate">{item.key}</td>
                            <td className="p-3 font-mono text-yellow-400">{item.proposedValue}</td>
                            <td className="p-3">
                                <p className="text-gray-300 mb-2 leading-relaxed"><span className="text-gray-500 font-medium">Why:</span> {item.reason}</p>
                                <p className="text-gray-400 italic text-xs"><span className="text-gray-500 font-medium">Hypothesis:</span> {item.impactHypothesis}</p>
                            </td>
                            <td className="p-3 text-center">
                                <span className={`inline-flex px-2 py-1 text-xs font-bold rounded border ${item.riskOfChange.toUpperCase() === 'HIGH' ? 'bg-red-900/30 text-red-400 border-red-800' :
                                    item.riskOfChange.toUpperCase() === 'MEDIUM' ? 'bg-yellow-900/30 text-yellow-400 border-yellow-800' :
                                        'bg-green-900/30 text-green-400 border-green-800'
                                    }`}>
                                    {item.riskOfChange.toUpperCase()}
                                </span>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};
