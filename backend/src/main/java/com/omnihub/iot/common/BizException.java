package com.omnihub.iot.common;

import org.springframework.http.HttpStatus;

/** Business exception carrying an HTTP status; the message is shown to end users as-is. */
public class BizException extends RuntimeException {

    private final HttpStatus status;

    public BizException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BizException badRequest(String msg) {
        return new BizException(HttpStatus.BAD_REQUEST, msg);
    }

    public static BizException notFound(String msg) {
        return new BizException(HttpStatus.NOT_FOUND, msg);
    }

    public static BizException conflict(String msg) {
        return new BizException(HttpStatus.CONFLICT, msg);
    }

    public static BizException forbidden(String msg) {
        return new BizException(HttpStatus.FORBIDDEN, msg);
    }
}
