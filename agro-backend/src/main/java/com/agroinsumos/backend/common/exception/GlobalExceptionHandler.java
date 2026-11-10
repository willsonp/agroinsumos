package com.agroinsumos.backend.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Traduce excepciones a RFC 9457 (application/problem+json). */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ProblemDetail negocio(BusinessException ex) {
        var pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("code", ex.getCode());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException ex) {
        Map<String, String> errores = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errores.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Datos inválidos");
        pd.setProperty("code", "VALIDACION");
        pd.setProperty("errores", errores);
        return pd;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail jsonInvalido(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return problema(HttpStatus.BAD_REQUEST, "JSON_INVALIDO", "Cuerpo de la petición inválido o con tipos incorrectos");
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ProblemDetail parametroInvalido(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return problema(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO", "Valor inválido para el parámetro '" + ex.getName() + "'");
    }

    @ExceptionHandler({BadCredentialsException.class})
    ProblemDetail credenciales(AuthenticationException ex) {
        return problema(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos");
    }

    @ExceptionHandler({LockedException.class, DisabledException.class})
    ProblemDetail bloqueado(AuthenticationException ex) {
        return problema(HttpStatus.UNAUTHORIZED, "USUARIO_BLOQUEADO", ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail noAutenticado(AuthenticationException ex) {
        return problema(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "Autenticación requerida");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denegado(AccessDeniedException ex) {
        return problema(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "No tiene permiso para esta operación");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrencia(OptimisticLockingFailureException ex) {
        return problema(HttpStatus.CONFLICT, "CONFLICTO_CONCURRENCIA",
                "El registro fue modificado por otro usuario. Recargue e intente de nuevo.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integridad(DataIntegrityViolationException ex) {
        log.warn("Violación de integridad: {}", ex.getMostSpecificCause().getMessage());
        return problema(HttpStatus.CONFLICT, "INTEGRIDAD", "El registro viola una restricción (duplicado o referencia inválida)");
    }

    private static ProblemDetail problema(HttpStatus status, String code, String detail) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }
}
