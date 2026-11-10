package com.agroinsumos.backend.ecf.emision;

import com.agroinsumos.backend.ecf.dto.EcfDtos.Item;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Cálculo de montos de líneas y totales del e-CF. Usa BigDecimal (el legacy usaba FLOAT(9,2),
 * con errores de redondeo en totales e ITBIS). Precios sin ITBIS incluido (IndicadorMontoGravado = 0).
 */
public final class EcfCalculadora {

    public static final BigDecimal TASA_ITBIS1 = new BigDecimal("18");
    public static final BigDecimal TASA_ITBIS2 = new BigDecimal("16");
    public static final BigDecimal TASA_ITBIS3 = BigDecimal.ZERO;
    private static final BigDecimal CIEN = new BigDecimal("100");

    private EcfCalculadora() {}

    public record Linea(int numero, Item item, BigDecimal descuento, BigDecimal montoItem) {}

    public record Totales(List<Linea> lineas,
                          BigDecimal montoGravadoI1, BigDecimal montoGravadoI2, BigDecimal montoGravadoI3,
                          BigDecimal montoExento, BigDecimal totalItbis1, BigDecimal totalItbis2,
                          BigDecimal totalItbis3, BigDecimal totalItbisRetenido, BigDecimal totalIsrRetenido) {

        public BigDecimal montoGravadoTotal() { return montoGravadoI1.add(montoGravadoI2).add(montoGravadoI3); }

        public BigDecimal totalItbis() { return totalItbis1.add(totalItbis2).add(totalItbis3); }

        public BigDecimal montoTotal() { return montoGravadoTotal().add(montoExento).add(totalItbis()); }

        public boolean tieneI1() { return montoGravadoI1.signum() > 0; }
        public boolean tieneI2() { return montoGravadoI2.signum() > 0; }
        public boolean tieneI3() { return montoGravadoI3.signum() > 0; }
        public boolean tieneExento() { return montoExento.signum() > 0; }
        public boolean tieneRetenciones() { return totalItbisRetenido.signum() > 0 || totalIsrRetenido.signum() > 0; }
    }

    public static Totales calcular(List<Item> items) {
        BigDecimal[] gravado = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal exento = BigDecimal.ZERO, retItbis = BigDecimal.ZERO, retIsr = BigDecimal.ZERO;
        List<Linea> lineas = new ArrayList<>(items.size());
        int n = 1;
        for (Item it : items) {
            BigDecimal bruto = it.cantidad().multiply(it.precioUnitario());
            BigDecimal desc = it.descuento() == null ? BigDecimal.ZERO : it.descuento();
            BigDecimal monto = bruto.subtract(desc).setScale(2, RoundingMode.HALF_UP);
            if (monto.signum() < 0) {
                throw new IllegalArgumentException("El descuento de la línea " + n + " supera su importe");
            }
            lineas.add(new Linea(n++, it, desc.setScale(2, RoundingMode.HALF_UP), monto));
            if (it.itbisRetenido() != null) retItbis = retItbis.add(it.itbisRetenido().setScale(2, RoundingMode.HALF_UP));
            if (it.isrRetenido() != null) retIsr = retIsr.add(it.isrRetenido().setScale(2, RoundingMode.HALF_UP));
            switch (it.indicadorFacturacion()) {
                case 1 -> gravado[0] = gravado[0].add(monto);
                case 2 -> gravado[1] = gravado[1].add(monto);
                case 3 -> gravado[2] = gravado[2].add(monto);
                case 4 -> exento = exento.add(monto);
                default -> throw new IllegalArgumentException("Indicador de facturación inválido");
            }
        }
        return new Totales(lineas, gravado[0], gravado[1], gravado[2], exento,
                itbis(gravado[0], TASA_ITBIS1), itbis(gravado[1], TASA_ITBIS2), itbis(gravado[2], TASA_ITBIS3),
                retItbis, retIsr);
    }

    private static BigDecimal itbis(BigDecimal base, BigDecimal tasa) {
        return base.multiply(tasa).divide(CIEN, 2, RoundingMode.HALF_UP);
    }
}
