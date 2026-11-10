package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.security.PermisoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Ejecuta los reportes declarados en los catálogos ({@link ReportesCatalogo}). */
@Service
@Transactional(readOnly = true)
public class ReporteService {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Map<String, Reporte> reportes = new LinkedHashMap<>();
    private final PermisoService permisos;
    private final Clock clock;

    public ReporteService(List<ReportesCatalogo> catalogos, PermisoService permisos, Clock clock) {
        catalogos.forEach(c -> c.reportes().forEach(r -> reportes.put(r.codigo(), r)));
        this.permisos = permisos;
        this.clock = clock;
    }

    /** Reportes que el usuario puede ver según las opciones de menú de cada grupo. */
    public List<Reporte.Info> disponibles() {
        return reportes.values().stream().filter(r -> permisos.tiene(r.grupo().permiso())).map(Reporte::info).toList();
    }

    public Reporte.Resultado ejecutar(Long cia, String codigo, Reporte.Parametros entrada) {
        Reporte r = reportes.get(codigo);
        if (r == null) throw new NotFoundException("Reporte", codigo);
        permisos.exigir(r.grupo().permiso());
        var p = normalizar(r, entrada);
        List<Map<String, Object>> filas = new ArrayList<>();
        for (var fila : r.consulta().apply(cia, p)) {
            Map<String, Object> f = new LinkedHashMap<>();
            fila.forEach((k, v) -> f.put(k, v instanceof java.sql.Date d ? d.toLocalDate()
                    : v instanceof Timestamp t ? t.toLocalDateTime().toLocalDate() : v));
            filas.add(f);
        }
        return new Reporte.Resultado(r.codigo(), r.titulo(), subtitulo(r, p), r.columnas(), filas, totales(r, filas));
    }

    /** CSV para Excel: UTF-8 con BOM, separador coma, decimales con punto. */
    public byte[] csv(Long cia, String codigo, Reporte.Parametros p) {
        var res = ejecutar(cia, codigo, p);
        var sb = new StringBuilder("﻿");
        sb.append(res.titulo()).append('\n').append(res.subtitulo()).append("\n\n");
        sb.append(String.join(",", res.columnas().stream().map(c -> csvCampo(c.titulo())).toList())).append('\n');
        for (var f : res.filas()) {
            sb.append(String.join(",", res.columnas().stream().map(c -> csvCampo(f.get(c.clave()))).toList())).append('\n');
        }
        if (!res.totales().isEmpty()) {
            List<String> t = new ArrayList<>();
            for (int i = 0; i < res.columnas().size(); i++) {
                var c = res.columnas().get(i);
                t.add(i == 0 ? "TOTAL" : csvCampo(res.totales().get(c.clave())));
            }
            sb.append(String.join(",", t)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Reporte.Parametros normalizar(Reporte r, Reporte.Parametros p) {
        LocalDate hoy = LocalDate.now(clock);
        LocalDate desde = p.desde(), hasta = p.hasta(), corte = p.corte();
        if (r.filtros().contains(Reporte.Filtro.RANGO_FECHAS)) {
            if (desde == null) desde = hoy.withDayOfMonth(1);
            if (hasta == null) hasta = hoy;
            if (hasta.isBefore(desde)) throw new BusinessException("RANGO_INVALIDO", "La fecha final es anterior a la inicial");
            if (desde.plusYears(3).isBefore(hasta)) throw new BusinessException("RANGO_INVALIDO", "Consulte como máximo 3 años");
        }
        if (r.filtros().contains(Reporte.Filtro.FECHA_CORTE) && corte == null) corte = hoy;
        return new Reporte.Parametros(desde, hasta, corte, p.clienteId(), p.vendedorId(), p.departamentoId(), p.almacenId(),
                p.proveedorId(), p.conExistencia());
    }

    private static String subtitulo(Reporte r, Reporte.Parametros p) {
        if (r.filtros().contains(Reporte.Filtro.RANGO_FECHAS)) return "Del " + DMY.format(p.desde()) + " al " + DMY.format(p.hasta());
        if (r.filtros().contains(Reporte.Filtro.FECHA_CORTE)) return "Al " + DMY.format(p.corte());
        return "";
    }

    private static Map<String, BigDecimal> totales(Reporte r, List<Map<String, Object>> filas) {
        Map<String, BigDecimal> t = new LinkedHashMap<>();
        for (var c : r.columnas()) {
            if (!c.totalizar()) continue;
            BigDecimal s = BigDecimal.ZERO;
            for (var f : filas) if (f.get(c.clave()) instanceof Number n) s = s.add(new BigDecimal(n.toString()));
            t.put(c.clave(), s);
        }
        // Márgenes del total: utilidad / venta
        if (t.containsKey("utilidad") && t.containsKey("venta") && t.get("venta").signum() != 0
                && r.columnas().stream().anyMatch(c -> c.clave().equals("margen"))) {
            t.put("margen", t.get("utilidad").multiply(BigDecimal.valueOf(100)).divide(t.get("venta"), 2, RoundingMode.HALF_UP));
        }
        return t;
    }

    private static String csvCampo(Object v) {
        if (v == null) return "";
        String s = v instanceof LocalDate d ? DMY.format(d) : v instanceof BigDecimal b ? b.toPlainString() : v.toString();
        return s.matches("[^,\"\\n\\r]*") ? s : "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
