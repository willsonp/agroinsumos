package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;

import java.math.BigDecimal;

/**
 * Línea de un asiento automático antes de resolver la cuenta.
 * La cuenta se toma de {@code codigo} (cuenta del departamento, cliente, proveedor o cuenta bancaria) y,
 * si viene vacía, de la configuración {@code clave}. {@code origenCodigo} explica de dónde salió el código.
 */
public record Partida(String codigo, String origenCodigo, ClaveContable clave, String descripcion,
                      BigDecimal debito, BigDecimal credito, String terceroTipo, Long terceroId) {

    public static Partida debe(String codigo, String origenCodigo, ClaveContable clave, BigDecimal monto, String descripcion) {
        return new Partida(codigo, origenCodigo, clave, descripcion, monto, BigDecimal.ZERO, null, null);
    }

    public static Partida haber(String codigo, String origenCodigo, ClaveContable clave, BigDecimal monto, String descripcion) {
        return new Partida(codigo, origenCodigo, clave, descripcion, BigDecimal.ZERO, monto, null, null);
    }

    public static Partida debe(ClaveContable clave, BigDecimal monto, String descripcion) {
        return debe(null, null, clave, monto, descripcion);
    }

    public static Partida haber(ClaveContable clave, BigDecimal monto, String descripcion) {
        return haber(null, null, clave, monto, descripcion);
    }

    /** Auxiliar de cliente (C) o proveedor (P). */
    public Partida tercero(String tipo, Long id) {
        return new Partida(codigo, origenCodigo, clave, descripcion, debito, credito, tipo, id);
    }

    public BigDecimal monto() { return debito.signum() > 0 ? debito : credito; }
}
