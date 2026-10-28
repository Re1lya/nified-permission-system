package com.cdwy.permission;

import com.cdwy.permission.dto.*;
import com.cdwy.permission.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RbacLifecycleWorkflowIntegrationTests extends WorkflowTestSupport {
    @Test void duplicateBusinessCodesAreConflictsAndDeletedUsersDisappearFromList() throws Exception {
        long system = system();
        assertThrows(ConflictException.class, () -> rbac.createSystem(new CreateSystemRequest("sys" + suffix, "重复", null)));
        assertThrows(ConflictException.class, () -> rbac.createRole(new CreateRoleRequest("role" + suffix, "重复", null)));
        var permission = new CreatePermissionRequest(system, "p" + suffix, "查询", "/query");
        rbac.createPermission(permission);
        assertThrows(ConflictException.class, () -> rbac.createPermission(permission));
        assertThrows(IllegalArgumentException.class, () -> rbac.createPermission(new CreatePermissionRequest(system, "UPPER:CODE", "非法编码", "/query")));
        assertThrows(IllegalArgumentException.class, () -> rbac.createPermission(new CreatePermissionRequest(system, null, "空编码", "/query")));
        assertTrue(call(get("/api/roles"), admin, null, 200).toString().contains("role" + suffix));
        assertTrue(call(get("/api/systems"), admin, null, 200).toString().contains("sys" + suffix));
        assertTrue(call(get("/api/users"), admin, null, 200).toString().contains("user" + suffix));
        rbac.deleteUser(user);
        assertFalse(call(get("/api/users"), admin, null, 200).toString().contains("user" + suffix));
    }
    @Test void systemRetirementRequiresInactivePermissionsAndReclaimedGrants() throws Exception {
        long system = system();
        long permission = rbac.createPermission(new CreatePermissionRequest(system, "p" + suffix, "查询", "/query")).id();
        rbac.assignPermission(role, permission);
        assertThrows(ConflictException.class, () -> rbac.deleteSystem(system));
        call(put("/api/permissions/" + permission), admin,
            Map.of("name", "按钮", "menuPath", "/query", "type", "BUTTON", "active", false), 200);
        assertEquals("BUTTON", db.queryForObject("select type from sys_permission where id=?", String.class, permission));
        assertThrows(ConflictException.class, () -> rbac.deleteSystem(system));
        rbac.removePermission(role, permission);
        rbac.deleteSystem(system);
        assertTrue(db.queryForObject("select deleted from biz_system where id=?", Boolean.class, system));
        assertTrue(rbac.listPermissions(system).isEmpty());
    }
    @Test void assignedRoleListingAndDisabledAccountHaveNoEffectivePermissions() throws Exception {
        rbac.assignRole(user, role);
        assertTrue(call(get("/api/users/" + user + "/roles"), admin, null, 200).toString().contains("role" + suffix));
        long system = system();
        long permission = rbac.createPermission(new CreatePermissionRequest(system, "p" + suffix, "查询", "/query")).id();
        rbac.assignPermission(role, permission);
        assertTrue(rbac.hasPermission(user, "p" + suffix));
        rbac.updateUser(user, new UpdateUserRequest("停用员工", false));
        assertTrue(rbac.effectivePermissions(user).isEmpty());
    }
}
