package com.cdwy.permission.controller;

import com.cdwy.permission.dto.LoginRequest;
import com.cdwy.permission.dto.LoginResponse;
import com.cdwy.permission.service.JwtTokenService;
import com.cdwy.permission.service.RbacService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RbacService rbacService;
    private final JwtTokenService jwtTokenService;

    private final com.cdwy.permission.platform.PlatformStore platform;

    public AuthController(RbacService rbacService, JwtTokenService jwtTokenService,
            com.cdwy.permission.platform.PlatformStore platform) {
        this.rbacService = rbacService;
        this.jwtTokenService = jwtTokenService;
        this.platform = platform;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return rbacService.login(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Long userId = jwtTokenService.subjectIfValid(authorization);
        jwtTokenService.revoke(authorization);
        if (userId != null) {
            platform.audit(userId, "LOGOUT", Long.toString(userId), "SUCCESS", userId);
        }
        return ResponseEntity.noContent().build();
    }
}
