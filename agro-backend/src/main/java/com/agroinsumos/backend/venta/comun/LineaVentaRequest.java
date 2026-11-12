package com.agroinsumos.backend.venta.comun;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Línea de venta. precioUnitario es opcional: si se omite se usa la lista de precios del cliente.
 * Cambiar el precio o superar el descuento máximo del artículo requiere rol SUPERVISOR o ADMIN.
 */
public record LineaVentaRequest(@NotNull Long articuloId,
                                @NotNull @DecimalMin("0.001") BigDecimal cantidad,
                                @DecimalMin("0") BigDecimal precioUnitario,
                                @DecimalMin("0") @DecimalMax("100") BigDecimal descuentoPorcentaje,
                                /** true: la cantidad y el precio son de la presentación del artículo (Saco, Caja); por defecto, la unidad base. */
                                Boolean empaque) {
    public LineaVentaRequest(Long articuloId, BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal descuentoPorcentaje) {
        this(articuloId, cantidad, precioUnitario, descuentoPorcentaje, null);
    }
}
