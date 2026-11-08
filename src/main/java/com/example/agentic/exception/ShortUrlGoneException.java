package com.example.agentic.exception;

import org.springframework.http.HttpStatus;

/** Short URL exists but is expired or deactivated: HTTP 410. */
public class ShortUrlGoneException extends ApiException {
    public ShortUrlGoneException(String code, String reason) {
        super(HttpStatus.GONE, code, reason);
    }
}
