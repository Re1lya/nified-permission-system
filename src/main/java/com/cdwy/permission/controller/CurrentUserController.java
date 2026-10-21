package com.cdwy.permission.controller;

import com.cdwy.permission.dto.PermissionResponse;
import com.cdwy.permission.exception.AuthenticationFailedException;
import com.cdwy.permission.repository.UserAccountRepository;
import com.cdwy.permission.service.JwtTokenService;
import com.cdwy.permission.service.RbacService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserController {
    private final JwtTokenService tokens;
    private final RbacService rbac;
    private final UserAccountRepository users;

    public CurrentUserController(JwtTokenService tokens, RbacService rbac, UserAccountRepository users) {
        this.tokens = tokens;
        this.rbac = rbac;
        this.users = users;
    }

    @GetMapping("/api/auth/me")
    public CurrentUser me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long id = tokens.requireUserId(authorization);
        var user = users.findById(id).orElseThrow(AuthenticationFailedException::new);
        return new CurrentUser(id, user.getUsername(), user.getDisplayName(), user.isAdministrator(),
                rbac.effectivePermissions(id));
    }

    public record CurrentUser(Long userId, String username, String displayName, boolean administrator,
                              List<PermissionResponse> permissions) { }
}
