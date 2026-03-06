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

import { getPermissions, updatePermissions } from './operatorApi';

describe('operatorApi', () => {
    beforeEach(() => {
        getMock.mockReset();
        postMock.mockReset();
    });

    it('calls get permissions endpoint', async () => {
        getMock.mockResolvedValue({ data: [] });

        await getPermissions();

        expect(getMock).toHaveBeenCalledWith('/api/v1/operator/permissions');
    });

    it('calls update permissions endpoint with updates payload', async () => {
        postMock.mockResolvedValue({ data: [] });

        await updatePermissions([{ key: 'scan.run_once', enabled: false }]);

        expect(postMock).toHaveBeenCalledWith(
            '/api/v1/operator/permissions',
            { updates: [{ key: 'scan.run_once', enabled: false }] },
        );
    });
});
