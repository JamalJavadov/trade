import React, { useEffect, useState } from 'react';
import { journalStore, type JournalItem } from '../store/journalStore';
import { JournalTable } from '../components/JournalTable';

export const JournalPage: React.FC = () => {
    const [items, setItems] = useState<JournalItem[]>([]);

    useEffect(() => {
        // refresh list from local storage on mount
        setItems(journalStore.list());

        // optional: trigger an interval to keep recent if user stays on page
        const interval = setInterval(() => {
            setItems(journalStore.list());
        }, 5000);
        return () => clearInterval(interval);
    }, []);

    return (
        <div className="max-w-6xl mx-auto p-6">
            <div className="mb-8">
                <h1 className="text-3xl font-bold bg-gradient-to-r from-blue-400 to-indigo-400 bg-clip-text text-transparent">
                    Trade Journal
                </h1>
                <p className="text-gray-400 mt-2">Historical setups and your execution feedback.</p>
            </div>

            <JournalTable items={items} />
        </div>
    );
};
