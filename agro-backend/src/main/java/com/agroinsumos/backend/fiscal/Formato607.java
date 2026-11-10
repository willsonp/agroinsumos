package com.agroinsumos.backend.fiscal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Formato 607: Ventas de Bienes y Servicios (Norma General 07-2018). */
public final class Formato607 {
    private Formato607() {}

    /** Una línea del 607, en el orden de los 23 campos del formato. */
    public record Linea(String rncCedula, String tipoId, String ncf, String ncfModificado, String tipoIngreso,
                        LocalDate fechaComprobante, LocalDate fechaRetencion, BigDecimal montoFacturado,
                        BigDecimal itbisFacturado, BigDecimal itbisRetenidoTerceros, BigDecimal itbisPercibido,
                        BigDecimal retencionRentaTerceros, BigDecimal isrPercibido, BigDecimal impuestoSelectivo,
                        BigDecimal otrosImpuestos, BigDecimal propinaLegal, BigDecimal efectivo,
                        BigDecimal chequeTransferencia, BigDecimal tarjeta, BigDecimal ventaCredito, BigDecimal bonos,
                        BigDecimal permuta, BigDecimal otrasFormas,
                        /** Informativo (no va en el TXT). */ String origen, Long origenId, String cliente) {}

    public record Resumen(int registros, BigDecimal totalFacturado, BigDecimal totalItbis, BigDecimal itbisRetenido,
                          BigDecimal isrRetenido, BigDecimal notasCredito) {}

    public record Reporte(String rnc, String periodo, List<Linea> lineas, Resumen resumen, List<String> advertencias) {}
}
