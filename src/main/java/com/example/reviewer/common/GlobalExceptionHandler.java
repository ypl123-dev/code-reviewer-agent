package com.example.reviewer.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ReviewerException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(ReviewerException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "code", 400,
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleSystem(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.internalServerError().body(Map.of(
                "code", 500,
                "message", e.getMessage()
        ));
    }
}
