package com.cdwy.permission.controller;

import com.cdwy.permission.service.RbacService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo/expenses")
public class DemoExpenseController {

    private final RbacService rbacService;

    public DemoExpenseController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Long userId = rbacService.requirePermission(authorization, "expense:list");
        return Map.of(
                "userId", userId,
                "items", java.util.List.of(
                        Map.of("id", 1, "title", "差旅报销", "amount", 1200),
                        Map.of("id", 2, "title", "办公用品报销", "amount", 260)));
    }
}
