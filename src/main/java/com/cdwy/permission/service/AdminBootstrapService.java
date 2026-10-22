package com.cdwy.permission.service;

import com.cdwy.permission.entity.UserAccount;
import com.cdwy.permission.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator-only startup command. Never exposed as an HTTP endpoint. */
@Service
public class AdminBootstrapService {
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;

    public AdminBootstrapService(UserAccountRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public void initialize(String username, String password) {
        if (username.isBlank() && password.isBlank()) return;
        if (username.isBlank() || username.length() > 64 || !username.equals(username.trim())) {
            throw new IllegalArgumentException("初始化管理员用户名必须为 1 至 64 个字符，首尾不能有空格");
        }
        PasswordPolicy.validate(password);
        var existing = users.findByUsernameForUpdate(username);
        if (existing.isPresent()) {
            if (!existing.get().isAdministrator()) {
                throw new IllegalStateException("初始化用户名已被普通用户使用，禁止自动提权");
            }
            return; // Repeat startup does not reset an existing password or sessions.
        }
        if (users.existsByAdministratorTrue()) {
            throw new IllegalStateException("已存在首次管理员，禁止通过更换启动参数创建第二个管理员");
        }
        UserAccount admin = new UserAccount(username, "系统管理员");
        admin.setAdministrator(true);
        admin.setBuiltInAdministrator(true);
        admin.setPasswordHash(encoder.encode(password));
        users.save(admin);
    }
}
