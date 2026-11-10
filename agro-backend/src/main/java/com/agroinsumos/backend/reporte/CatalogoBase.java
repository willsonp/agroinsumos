package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.reporte.Reporte.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/** Apoyo para declarar reportes con SQL y parámetros opcionales tipados (null = sin filtro). */
abstract class CatalogoBase implements ReportesCatalogo {

    /** Factura que cuenta como venta: no anulada, o anulada por una devolución (la devolución la compensa). */
    static final String FACTURA_VALIDA = "(f.estado <> 'ANULADA' or exists (select 1 from devolucion dv where dv.factura_id = f.id))";
    static final String TASA_ITBIS = "(case a.indicador_itbis when 1 then 18 when 2 then 16 else 0 end)";

    protected final NamedParameterJdbcTemplate jdbc;

    protected CatalogoBase(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    protected Reporte reporte(String codigo, String titulo, Grupo grupo, String descripcion, String legado, List<Filtro> filtros,
                              List<Columna> columnas, String sql) {
        return new Reporte(codigo, titulo, grupo, descripcion, legado, filtros, columnas, (cia, p) -> filas(sql, cia, p));
    }

    protected List<Map<String, Object>> filas(String sql, Long cia, Parametros p) {
        var m = new MapSqlParameterSource()
                .addValue("cia", cia, Types.BIGINT)
                .addValue("desde", p.desde() == null ? null : Date.valueOf(p.desde()), Types.DATE)
                .addValue("hasta", p.hasta() == null ? null : Date.valueOf(p.hasta()), Types.DATE)
                .addValue("corte", p.corte() == null ? null : Date.valueOf(p.corte()), Types.DATE)
                .addValue("cliente", p.clienteId(), Types.BIGINT)
                .addValue("vendedor", p.vendedorId(), Types.BIGINT)
                .addValue("departamento", p.departamentoId(), Types.BIGINT)
                .addValue("almacen", p.almacenId(), Types.BIGINT)
                .addValue("proveedor", p.proveedorId(), Types.BIGINT)
                .addValue("conExistencia", p.conExistencia(), Types.BOOLEAN);
        return jdbc.queryForList(sql, m);
    }
}
