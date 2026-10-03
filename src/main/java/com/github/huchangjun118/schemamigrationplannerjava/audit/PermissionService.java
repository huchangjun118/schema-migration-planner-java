package com.github.huchangjun118.schemamigrationplannerjava.audit;

import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 权限校验。越权请求被拒绝并给出可区分的原因码，同时写审计。
 */
@Service
public class PermissionService {

    /** 动作 -> 允许的角色 */
    private static final Map<String, Set<Role>> POLICY = Map.of(
            "PLAN_CREATE", EnumSet.of(Role.ADMIN, Role.APPLIER),
            "PLAN_APPLY", EnumSet.of(Role.ADMIN, Role.APPLIER),
            "ROLLBACK", EnumSet.of(Role.ADMIN),
            "VERSION_CREATE", EnumSet.of(Role.ADMIN, Role.APPLIER),
            "VERSION_VIEW", EnumSet.of(Role.ADMIN, Role.APPLIER, Role.VIEWER),
            "AUDIT_VIEW", EnumSet.of(Role.ADMIN, Role.APPLIER, Role.VIEWER),
            "ROLE_MANAGE", EnumSet.of(Role.ADMIN)
    );

    private final MetadataStore store;
    private final AuditService auditService;

    public PermissionService(MetadataStore store, AuditService auditService) {
        this.store = store;
        this.auditService = auditService;
    }

    /**
     * 校验操作者是否有权限执行动作。
     *
     * @throws AccessDeniedException 越权时抛出（含原因码）
     */
    public void require(String operator, String action) {
        Optional<String> roleOpt = store.getRole(operator);
        if (roleOpt.isEmpty()) {
            deny("UNKNOWN_OPERATOR", operator, action,
                    "操作者 " + operator + " 未注册，无法执行 " + action);
        }
        Role role = Role.parse(roleOpt.get());
        if (role == null) {
            deny("UNKNOWN_ROLE", operator, action,
                    "操作者 " + operator + " 的角色 " + roleOpt.get() + " 非法");
        }
        Set<Role> allowed = POLICY.get(action);
        if (allowed == null) {
            deny("UNKNOWN_ACTION", operator, action, "未知动作 " + action);
        }
        if (!allowed.contains(role)) {
            String reason = switch (action) {
                case "ROLLBACK" -> "ROLLBACK_REQUIRES_ADMIN";
                case "PLAN_APPLY" -> "APPLY_REQUIRES_APPLIER";
                case "PLAN_CREATE" -> "PLAN_REQUIRES_APPLIER";
                case "VERSION_CREATE" -> "VERSION_CREATE_REQUIRES_APPLIER";
                case "AUDIT_VIEW" -> "AUDIT_VIEW_REQUIRES_VIEWER";
                case "ROLE_MANAGE" -> "ROLE_MANAGE_REQUIRES_ADMIN";
                default -> "PERMISSION_DENIED";
            };
            deny(reason, operator, action,
                    "操作者 " + operator + "（角色 " + role + "）无权执行 " + action
                            + "，需要角色 " + allowed);
        }
    }

    private void deny(String reasonCode, String operator, String action, String message) {
        auditService.recordDenied(operator, action, reasonCode, message);
        throw new AccessDeniedException(reasonCode, operator, action, message);
    }
}
