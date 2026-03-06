import { apiClient } from './axiosSetup';

export type PermissionGroup = 'SCAN' | 'SETTINGS' | 'AI' | 'DEMO' | 'JOURNAL' | 'EXPORTS' | 'ERRORS' | string;
export type PermissionDangerLevel = 'LOW' | 'MED' | 'HIGH' | string;

export interface PermissionItem {
    key: string;
    title: string;
    description: string;
    group: PermissionGroup;
    dangerLevel: PermissionDangerLevel;
    enabled: boolean;
    updatedAt: string;
    updatedBy: string;
}

export interface PermissionUpdate {
    key: string;
    enabled: boolean;
}

export const getPermissions = async (): Promise<PermissionItem[]> => {
    const response = await apiClient.get<PermissionItem[]>('/api/v1/operator/permissions');
    return Array.isArray(response.data) ? response.data : [];
};

export const updatePermissions = async (
    updates: PermissionUpdate[],
): Promise<PermissionItem[]> => {
    const response = await apiClient.post<PermissionItem[]>(
        '/api/v1/operator/permissions',
        { updates },
    );
    return Array.isArray(response.data) ? response.data : [];
};
