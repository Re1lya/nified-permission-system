package com.cdwy.permission.exception;

public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        this("用户名或密码错误");
    }

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
