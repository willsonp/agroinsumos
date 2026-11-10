package com.agroinsumos.backend.common.pago;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PagoRequest(@NotNull FormaPago forma, @NotNull @DecimalMin("0.01") BigDecimal monto,
                          @Size(max = 40) String referencia, @Size(max = 45) String banco) {}
