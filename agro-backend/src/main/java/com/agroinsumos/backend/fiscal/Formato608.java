package com.agroinsumos.backend.fiscal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Formato 608: comprobantes fiscales anulados (Norma General 07-2018). */
public final class Formato608 {
    private Formato608() {}

    /** Tipos de anulación de la DGII. */
    public static final Map<String, String> TIPOS = Map.of(
            "01", "Deterioro de factura preimpresa",
            "02", "Errores de impresión (factura preimpresa)",
            "03", "Impresión defectuosa",
            "04", "Corrección de la información",
            "05", "Cambio de productos",
            "06", "Devolución de productos",
            "07", "Omisión de productos",
            "08", "Errores en secuencia de NCF",
            "09", "Por cese de operaciones",
            "10", "Pérdida o hurto de talonarios");

    public record Linea(String ncf, LocalDate fechaComprobante, String tipoAnulacion, String descripcionTipo,
                        String origen, Long origenId, String motivo) {}

    public record Reporte(String rnc, String periodo, List<Linea> lineas, List<String> advertencias) {}
}
