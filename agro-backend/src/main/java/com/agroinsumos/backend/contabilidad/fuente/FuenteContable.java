package com.agroinsumos.backend.contabilidad.fuente;

import java.time.LocalDate;
import java.util.List;

/**
 * Estrategia de contabilización de un tipo de documento (Open/Closed: un documento nuevo = una fuente nueva).
 * El generador pide los documentos del período aún sin asiento y los anulados ya contabilizados (para reversarlos).
 */
public interface FuenteContable {

    /** Valor de asiento.origen_tipo. */
    String origen();

    String descripcion();

    List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta);

    /** Documentos anulados que tienen asiento y aún no tienen reverso (de cualquier fecha). */
    List<Long> anuladosPorReversar(Long cia);
}
