package com.omnihub.iot.common;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<ApiResponse<Object>> resp(HttpStatus status, String msg, Object data) {
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), msg, data));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> biz(BizException e) {
        return resp(e.getStatus(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        String first = errors.isEmpty() ? "参数错误" : errors.values().iterator().next();
        return resp(HttpStatus.BAD_REQUEST, first, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> constraint(ConstraintViolationException e) {
        return resp(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class })
    public ResponseEntity<ApiResponse<Object>> badInput(Exception e) {
        return resp(HttpStatus.BAD_REQUEST, "请求参数格式错误", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> denied(AccessDeniedException e) {
        return resp(HttpStatus.FORBIDDEN, "无权限执行该操作", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Object>> auth(AuthenticationException e) {
        return resp(HttpStatus.UNAUTHORIZED, "未登录或登录已过期", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return resp(HttpStatus.CONFLICT, "数据冲突：标识重复或存在关联数据", null);
    }

    @ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
    public ResponseEntity<ApiResponse<Object>> notFound(Exception e) {
        return resp(HttpStatus.NOT_FOUND, "资源不存在", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> method(HttpRequestMethodNotSupportedException e) {
        return resp(HttpStatus.METHOD_NOT_ALLOWED, "不支持的请求方法", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> other(Exception e) {
        log.error("Unhandled exception", e);
        return resp(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误", null);
    }
}
