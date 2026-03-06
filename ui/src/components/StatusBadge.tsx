import React from 'react';
import type { JournalStatus } from '../store/journalStore';

interface StatusBadgeProps {
    status: JournalStatus; // NEW, OPEN, CLOSED
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status }) => {
    let bg = '';
    let text = '';

    if (status === 'NEW') {
        bg = 'bg-blue-500/20';
        text = 'text-blue-400';
    } else if (status === 'OPEN') {
        bg = 'bg-yellow-500/20';
        text = 'text-yellow-400';
    } else if (status === 'CLOSED') {
        bg = 'bg-gray-500/20';
        text = 'text-gray-400';
    }

    return (
        <span className={`px-2 py-1 flex items-center justify-center text-xs font-bold rounded-full border border-current ${bg} ${text}`}>
            {status}
        </span>
    );
};
