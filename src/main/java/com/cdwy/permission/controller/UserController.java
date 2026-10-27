package com.cdwy.permission.controller;

import com.cdwy.permission.dto.CreateUserRequest;
import com.cdwy.permission.dto.PermissionCheckResponse;
import com.cdwy.permission.dto.PermissionResponse;
import com.cdwy.permission.dto.RoleResponse;
import com.cdwy.permission.dto.SetPasswordRequest;
import com.cdwy.permission.dto.UpdateUserRequest;
import com.cdwy.permission.dto.UserResponse;
import com.cdwy.permission.service.RbacService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 用户资源的 HTTP 入口：@Valid 校验请求结构，业务实现交给 RbacService。
 * /api/users 属于默认管理员接口；带部门范围的管理门户主要走 platform.PlatformController。
 * 路径中的 /roles/{roleId} 表示授权关系子资源，便于对同一关系使用 PUT 授予、DELETE 回收。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final RbacService rbacService;

    public UserController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rbacService.createUser(request));
    }

    @GetMapping
    public List<UserResponse> list() {
        return rbacService.listUsers();
    }

    @PutMapping("/{userId}")
    public UserResponse update(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return rbacService.updateUser(userId, request);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@PathVariable Long userId) {
        rbacService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/password")
    public ResponseEntity<Void> setPassword(
            @PathVariable Long userId,
            @Valid @RequestBody SetPasswordRequest request) {
        rbacService.setPassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    // 为什么需要此 API：用户任职与角色权限定义分离，分配角色只变更用户—角色关系。
    @PutMapping("/{userId}/roles/{roleId}")
    public ResponseEntity<Void> assignRole(@PathVariable Long userId, @PathVariable Long roleId) {
        rbacService.assignRole(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/roles/{roleId}")
    public ResponseEntity<Void> removeRole(@PathVariable Long userId, @PathVariable Long roleId) {
        rbacService.removeRole(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/roles")
    public List<RoleResponse> listRoles(@PathVariable Long userId) {
        return rbacService.listUserRoles(userId);
    }

    @GetMapping("/{userId}/permissions")
    public List<PermissionResponse> listPermissions(@PathVariable Long userId) {
        return rbacService.effectivePermissions(userId);
    }

    @GetMapping("/{userId}/permissions/check")
    public PermissionCheckResponse checkPermission(
            @PathVariable Long userId,
            @RequestParam String code) {
        return new PermissionCheckResponse(userId, code, rbacService.hasPermission(userId, code));
    }
}
