package com.agroinsumos.backend.ecf.emision;

import com.agroinsumos.backend.ecf.dto.EcfDtos.Item;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Cálculo de montos de líneas y totales del e-CF. Usa BigDecimal (el legacy usaba FLOAT(9,2),
 * con errores de redondeo en totales e ITBIS). Precios sin ITBIS incluido (IndicadorMontoGravado = 0).
 *
 * <p>Los precios se escriben con ITBIS (RD$500 la unidad, RD$6,000 la caja) y se guardan sin ITBIS con 4 decimales
 * (423.7288). Para que el cliente pague exactamente el precio con ITBIS y base + ITBIS cuadren al centavo, cada línea
 * gravada parte de su total con ITBIS: total = cantidad × precio × (1 + tasa) redondeado a 2 (6,000.00); base =
 * total / (1 + tasa) redondeada (5,084.75) e ITBIS = total − base (915.25). Antes se redondeaba la base y se le volvía
 * a sumar el 18 % (5,084.75 + 915.26 = 6,000.01). Con precios de hasta 2 decimales el resultado es el mismo de siempre.
 * La diferencia contra el 18 % exacto es de fracciones de centavo, dentro de la tolerancia de redondeo del e-CF.</p>
 */
public final class EcfCalculadora {

    public static final BigDecimal TASA_ITBIS1 = new BigDecimal("18");
    public static final BigDecimal TASA_ITBIS2 = new BigDecimal("16");
    public static final BigDecimal TASA_ITBIS3 = BigDecimal.ZERO;
    private static final BigDecimal CIEN = new BigDecimal("100");

    private EcfCalculadora() {}

    /** @param itbis ITBIS de la línea (total con ITBIS − montoItem); la suma por tasa es el ITBIS del documento */
    public record Linea(int numero, Item item, BigDecimal descuento, BigDecimal montoItem, BigDecimal itbis) {}

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
        BigDecimal[] impuesto = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal exento = BigDecimal.ZERO, retItbis = BigDecimal.ZERO, retIsr = BigDecimal.ZERO;
        List<Linea> lineas = new ArrayList<>(items.size());
        int n = 1;
        for (Item it : items) {
            BigDecimal bruto = it.cantidad().multiply(it.precioUnitario());
            BigDecimal desc = it.descuento() == null ? BigDecimal.ZERO : it.descuento();
            BigDecimal neto = bruto.subtract(desc);
            if (neto.signum() < 0) {
                throw new IllegalArgumentException("El descuento de la línea " + n + " supera su importe");
            }
            BigDecimal tasa = switch (it.indicadorFacturacion()) {
                case 1 -> TASA_ITBIS1; case 2 -> TASA_ITBIS2; default -> BigDecimal.ZERO;
            };
            BigDecimal monto, itbisLinea;
            if (tasa.signum() > 0) {
                // Desde el total con ITBIS (precio sin redondear): el cliente paga el precio con ITBIS exacto
                BigDecimal factor = BigDecimal.ONE.add(tasa.divide(CIEN));
                BigDecimal total = neto.multiply(factor).setScale(2, RoundingMode.HALF_UP);
                monto = total.divide(factor, 2, RoundingMode.HALF_UP);
                itbisLinea = total.subtract(monto);
            } else {
                monto = neto.setScale(2, RoundingMode.HALF_UP);
                itbisLinea = BigDecimal.ZERO.setScale(2);
            }
            lineas.add(new Linea(n++, it, desc.setScale(2, RoundingMode.HALF_UP), monto, itbisLinea));
            if (it.itbisRetenido() != null) retItbis = retItbis.add(it.itbisRetenido().setScale(2, RoundingMode.HALF_UP));
            if (it.isrRetenido() != null) retIsr = retIsr.add(it.isrRetenido().setScale(2, RoundingMode.HALF_UP));
            switch (it.indicadorFacturacion()) {
                case 1 -> { gravado[0] = gravado[0].add(monto); impuesto[0] = impuesto[0].add(itbisLinea); }
                case 2 -> { gravado[1] = gravado[1].add(monto); impuesto[1] = impuesto[1].add(itbisLinea); }
                case 3 -> gravado[2] = gravado[2].add(monto);
                case 4 -> exento = exento.add(monto);
                default -> throw new IllegalArgumentException("Indicador de facturación inválido");
            }
        }
        return new Totales(lineas, gravado[0], gravado[1], gravado[2], exento,
                impuesto[0].setScale(2), impuesto[1].setScale(2), BigDecimal.ZERO.setScale(2), retItbis, retIsr);
    }
}
