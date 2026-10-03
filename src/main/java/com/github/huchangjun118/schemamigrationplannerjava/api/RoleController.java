package com.github.huchangjun118.schemamigrationplannerjava.api;

import com.github.huchangjun118.schemamigrationplannerjava.api.dto.RoleRequest;
import com.github.huchangjun118.schemamigrationplannerjava.audit.PermissionService;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理 API。
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final MetadataStore store;
    private final PermissionService permissionService;

    public RoleController(MetadataStore store, PermissionService permissionService) {
        this.store = store;
        this.permissionService = permissionService;
    }

    /** 查询所有角色 */
    @GetMapping
    public List<MetadataStore.RoleRow> list() {
        return store.listRoles();
    }

    /** 设置操作者角色（需 ADMIN） */
    @PostMapping
    public void setRole(@RequestBody RoleRequest request,
                        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        permissionService.require(operator, "ROLE_MANAGE");
        store.setRole(request.operator(), request.role());
    }
}
