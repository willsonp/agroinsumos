package com.agroinsumos.backend.common.pago;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Forma de pago de un documento. {@code cuentaBancariaId} vincula cheques/transferencias con una cuenta
 * del módulo Banco: el movimiento bancario se genera automáticamente.
 */
public record PagoRequest(@NotNull FormaPago forma, @NotNull @DecimalMin("0.01") BigDecimal monto,
                          @Size(max = 40) String referencia, @Size(max = 45) String banco, Long cuentaBancariaId) {

    public PagoRequest(FormaPago forma, BigDecimal monto, String referencia, String banco) {
        this(forma, monto, referencia, banco, null);
    }
}
