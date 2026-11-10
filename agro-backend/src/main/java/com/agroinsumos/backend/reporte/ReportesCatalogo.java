package com.agroinsumos.backend.reporte;

import java.util.List;

/** Un grupo de reportes declarados (cada implementación es un @Component). */
public interface ReportesCatalogo {
    List<Reporte> reportes();
}
