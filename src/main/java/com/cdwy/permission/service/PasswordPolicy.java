package com.cdwy.permission.service;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() { }

    public static void validate(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码至少 8 个字符，UTF-8 编码不超过 72 字节");
        }
        int kinds=0;
        if(password.matches(".*[A-Z].*")) kinds++;
        if(password.matches(".*[a-z].*")) kinds++;
        if(password.matches(".*[0-9].*")) kinds++;
        if(password.matches(".*[^A-Za-z0-9].*")) kinds++;
        if(kinds<3)throw new IllegalArgumentException("密码需包含大写、小写、数字、符号中的至少三类");
    }
}
