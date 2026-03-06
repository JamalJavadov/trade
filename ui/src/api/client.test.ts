import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock } = vi.hoisted(() => ({
    getMock: vi.fn(),
    postMock: vi.fn(),
}));

vi.mock('./axiosSetup', () => ({
    apiClient: {
        get: getMock,
        post: postMock,
    },
}));

import { getLiveAiModels, testLiveAiModels, updateLiveAiModels } from './client';

describe('client ai models api', () => {
    beforeEach(() => {
        getMock.mockReset();
        postMock.mockReset();
    });

    it('calls live ai model endpoints', async () => {
        getMock.mockResolvedValue({ data: { mode: 'LIVE', tasks: [] } });
        postMock.mockResolvedValue({ data: { mode: 'LIVE', tasks: [] } });

        await getLiveAiModels();
        await updateLiveAiModels({ revertToDefaults: false, tasks: [] });
        await testLiveAiModels('SUGGESTION_BATCH');

        expect(getMock).toHaveBeenCalledWith('/api/v1/ai/models');
        expect(postMock).toHaveBeenNthCalledWith(1, '/api/v1/ai/models', { revertToDefaults: false, tasks: [] });
        expect(postMock).toHaveBeenNthCalledWith(2, '/api/v1/ai/models/test', { taskType: 'SUGGESTION_BATCH' });
    });
});
