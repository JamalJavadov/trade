import React, { useState } from 'react';
import { Check, Copy } from 'lucide-react';

interface JsonBlockProps {
    label: string;
    data: any;
    copyDisabled?: boolean;
    copyDisabledTitle?: string;
    lockMessage?: string;
}

export const JsonBlock: React.FC<JsonBlockProps> = ({ label, data, copyDisabled, copyDisabledTitle, lockMessage }) => {
    const [copied, setCopied] = useState(false);

    const jsonString = JSON.stringify(data, null, 2);

    const handleCopy = () => {
        if (copyDisabled) return;
        navigator.clipboard.writeText(jsonString);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    return (
        <div className="bg-gray-800 rounded-lg overflow-hidden border border-gray-700">
            <div className="flex items-center justify-between px-4 py-2 bg-gray-700 border-b border-gray-600">
                <span className="text-sm font-semibold text-gray-200">{label}</span>
                <button
                    onClick={handleCopy}
                    disabled={copyDisabled}
                    className={`transition-colors ${copyDisabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-400 hover:text-white'}`}
                    title={copyDisabled ? (copyDisabledTitle ?? 'Copy disabled') : 'Copy JSON'}
                >
                    {copied ? <Check size={16} className="text-green-400" /> : <Copy size={16} />}
                </button>
            </div>
            {lockMessage && (
                <div className="px-4 py-2 border-b border-red-800/40 bg-red-900/30 text-xs text-red-200">
                    {lockMessage}
                </div>
            )}
            <div className="p-4 overflow-x-auto text-sm text-green-400 font-mono">
                <pre>{jsonString}</pre>
            </div>
        </div>
    );
};
