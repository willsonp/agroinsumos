package com.agroinsumos.backend.contabilidad.fuente;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Documento operativo listo para contabilizar (factura, recibo, compra...). */
public record DocumentoContable(String origenTipo, Long origenId, LocalDate fecha, String concepto, List<Partida> partidas) {
    public DocumentoContable(String origenTipo, Long origenId, LocalDate fecha, String concepto) {
        this(origenTipo, origenId, fecha, concepto.length() > 200 ? concepto.substring(0, 200) : concepto, new ArrayList<>());
    }

    public DocumentoContable add(Partida p) {
        if (p.monto() != null && p.monto().signum() > 0) partidas.add(p);
        return this;
    }
}
