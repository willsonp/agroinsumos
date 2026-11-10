package com.agroinsumos.backend.common.exception;

import org.springframework.http.HttpStatus;

/** Regla de negocio violada. Reemplaza los JOptionPane de validación del Swing. */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BusinessException(String code, String message) {
        this(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
