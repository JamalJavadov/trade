package com.tradebot.operator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OperatorPermissionInterceptor implements HandlerInterceptor {

    private final ObjectProvider<OperatorPermissionService> permissionServiceProvider;

    public OperatorPermissionInterceptor(ObjectProvider<OperatorPermissionService> permissionServiceProvider) {
        this.permissionServiceProvider = permissionServiceProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequiresPermission requiresPermission = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequiresPermission.class);
        if (requiresPermission == null) {
            requiresPermission = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequiresPermission.class);
        }
        if (requiresPermission == null) {
            return true;
        }

        String permissionKey = requiresPermission.value();
        if (permissionKey == null || permissionKey.isBlank()) {
            return true;
        }

        OperatorPermissionService permissionService = permissionServiceProvider.getIfAvailable();
        if (permissionService == null) {
            return true;
        }

        permissionService.requirePermissionEnabled(permissionKey);
        return true;
    }
}
