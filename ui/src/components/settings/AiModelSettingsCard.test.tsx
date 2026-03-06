import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiModelSettingsCard } from './AiModelSettingsCard';

const enabledResponse = {
    mode: 'LIVE' as const,
    controlsEnabled: true,
    allowlist: ['model-a', 'model-b'],
    tasks: [{
        taskType: 'SUGGESTION_BATCH' as const,
        primaryModel: 'model-a',
        fallbackModels: ['model-b'],
        lastCall: {
            status: 'SUCCESS',
            traceId: 'trace-1',
            latencyMs: 123,
            modelUsed: 'model-a',
            calledAt: '2026-03-01T00:00:00Z',
        },
    }],
};

describe('AiModelSettingsCard', () => {
    afterEach(() => {
        cleanup();
    });

    it('renders disabled banner when controls are disabled', async () => {
        render(
            <AiModelSettingsCard
                title="AI Models (LIVE)"
                subtitle="subtitle"
                modeLabel="LIVE"
                fetchSettings={vi.fn().mockResolvedValue({ ...enabledResponse, controlsEnabled: false })}
                updateSettings={vi.fn().mockResolvedValue(enabledResponse)}
                testCall={vi.fn().mockResolvedValue({
                    mode: 'LIVE',
                    taskType: 'SUGGESTION_BATCH',
                    ok: true,
                    simulated: true,
                    traceId: 'test-1',
                    latencyMs: 0,
                    modelUsed: 'model-a',
                    payload: { message: 'Dummy' },
                })}
            />,
        );

        await waitFor(() => {
            expect(screen.getByText(/Controls are disabled/i)).toBeInTheDocument();
        });
    });

    it('saves model updates and supports revert + test', async () => {
        const user = userEvent.setup();
        const fetchSettings = vi.fn().mockResolvedValue(enabledResponse);
        const updateSettings = vi.fn().mockResolvedValue(enabledResponse);
        const testCall = vi.fn().mockResolvedValue({
            mode: 'LIVE' as const,
            taskType: 'SUGGESTION_BATCH' as const,
            ok: true,
            simulated: true,
            traceId: 'test-1',
            latencyMs: 0,
            modelUsed: 'model-a',
            payload: { message: 'Dummy' },
        });

        render(
            <AiModelSettingsCard
                title="AI Models (LIVE)"
                subtitle="subtitle"
                modeLabel="LIVE"
                fetchSettings={fetchSettings}
                updateSettings={updateSettings}
                testCall={testCall}
            />,
        );

        await waitFor(() => {
            expect(screen.getByText('AI Models (LIVE)')).toBeInTheDocument();
        });

        await user.selectOptions(screen.getByLabelText(/SUGGESTION_BATCH Primary/i), 'model-b');
        await user.click(screen.getByRole('button', { name: 'Save' }));

        expect(updateSettings).toHaveBeenCalledWith({
            revertToDefaults: false,
            tasks: [{
                taskType: 'SUGGESTION_BATCH',
                primaryModel: 'model-b',
                fallbackModels: [],
            }],
        });

        await user.click(screen.getByRole('button', { name: 'Revert to defaults' }));
        expect(updateSettings).toHaveBeenCalledWith({
            revertToDefaults: true,
            tasks: [],
        });

        await user.click(screen.getByRole('button', { name: 'Test call' }));
        expect(testCall).toHaveBeenCalledWith('SUGGESTION_BATCH');
    });
});
