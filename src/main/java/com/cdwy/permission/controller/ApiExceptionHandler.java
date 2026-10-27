package com.cdwy.permission.controller;

import com.cdwy.permission.exception.ConflictException;
import com.cdwy.permission.exception.AuthenticationFailedException;
import com.cdwy.permission.exception.ForbiddenException;
import com.cdwy.permission.exception.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误出口：参数不合法 400、身份无效 401、权限不足 403、资源不存在 404、冲突 409。
 * 事务代理先对抛出的业务异常执行回滚，再由这里转换 HTTP 响应；返回错误本身不是回滚机制。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> missingRow(Exception exception) {
        return response(HttpStatus.NOT_FOUND, "记录不存在或已删除");
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException exception) {
        return response(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(AuthenticationFailedException exception) {
        return response(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return response(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> badInput(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, exception instanceof IllegalArgumentException
                ? exception.getMessage() : "请求格式不正确，请检查字段类型和 JSON");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> constraint(Exception exception) {
        return response(HttpStatus.CONFLICT, "数据重复或关联约束冲突，请刷新后重试");
    }

    private ResponseEntity<Map<String, String>> response(HttpStatus status, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
