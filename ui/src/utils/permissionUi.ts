export function disabledByPermissionTooltip(permissionKey: string, allowed: boolean): string | undefined {
    if (allowed) {
        return undefined;
    }
    return `Disabled by operator permission: ${permissionKey}`;
}

export function firstDeniedPermission(
    permissionKeys: string[],
    can: (permissionKey: string) => boolean,
): string | null {
    for (const key of permissionKeys) {
        if (!can(key)) {
            return key;
        }
    }
    return null;
}
