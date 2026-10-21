package com.cdwy.permission.config;

import com.cdwy.permission.service.AdminBootstrapService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final AdminBootstrapService bootstrap;
    private final String username;
    private final String password;

    public AdminBootstrap(AdminBootstrapService bootstrap,
            @Value("${permission.bootstrap.username:}") String username,
            @Value("${permission.bootstrap.password:}") String password) {
        this.bootstrap = bootstrap;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.initialize(username, password);
    }
}
