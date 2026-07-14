package com.example.reviewer.common;

/**
 * 业务异常
 */
public class ReviewerException extends RuntimeException {

    public ReviewerException(String message) {
        super(message);
    }

    public ReviewerException(String message, Throwable cause) {
        super(message, cause);
    }
}
