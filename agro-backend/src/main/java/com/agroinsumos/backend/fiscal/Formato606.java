package com.agroinsumos.backend.fiscal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Formato 606: Compras de Bienes y Servicios (Norma General 07-2018). */
public final class Formato606 {
    private Formato606() {}

    /** Una línea del 606, en el orden de los 23 campos del formato. */
    public record Linea(String rncCedula, String tipoId, String tipoBienServicio, String ncf, String ncfModificado,
                        LocalDate fechaComprobante, LocalDate fechaPago, BigDecimal montoServicios, BigDecimal montoBienes,
                        BigDecimal totalMontoFacturado, BigDecimal itbisFacturado, BigDecimal itbisRetenido,
                        BigDecimal itbisProporcionalidad, BigDecimal itbisLlevadoCosto, BigDecimal itbisPorAdelantar,
                        BigDecimal itbisPercibido, String tipoRetencionIsr, BigDecimal montoRetencionRenta,
                        BigDecimal isrPercibido, BigDecimal impuestoSelectivo, BigDecimal otrosImpuestos,
                        BigDecimal propinaLegal, String formaPago,
                        /** Informativo (no va en el TXT). */ String origen, Long origenId, String suplidor) {}

    public record Resumen(int registros, BigDecimal totalFacturado, BigDecimal totalItbis, BigDecimal itbisRetenido,
                          BigDecimal isrRetenido, BigDecimal itbisPorAdelantar) {}

    public record Reporte(String rnc, String periodo, List<Linea> lineas, Resumen resumen, List<String> advertencias) {}
}
