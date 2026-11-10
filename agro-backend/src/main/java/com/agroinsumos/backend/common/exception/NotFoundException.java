package com.agroinsumos.backend.common.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends BusinessException {
    public NotFoundException(String recurso, Object id) {
        super(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", recurso + " no encontrado: " + id);
    }
}
