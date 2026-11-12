package com.agroinsumos.backend.inventario.kardex;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Kárdex de un artículo para consultar e imprimir: saldo anterior al período, movimientos en orden cronológico con
 * el saldo corrido (del almacén elegido o de todos) y totales de entradas y salidas. Las fechas son las de RD.
 */
@Service
@RequiredArgsConstructor
public class KardexService {

    /** Tope de filas por consulta (para imprimir más, se acota el período). */
    static final int MAX_FILAS = 20_000;

    private final NamedParameterJdbcTemplate jdbc;

    public record Fila(Long id, Instant fecha, String tipo, String documentoTipo, Long documentoId, Long almacenId, String almacen,
                       BigDecimal cantidad, BigDecimal costoUnitario, BigDecimal saldo, String usuario) {}

    public record Reporte(Long articuloId, String codigo, String articulo, String unidad, BigDecimal costoPromedio,
                          LocalDate desde, LocalDate hasta, Long almacenId, String almacen,
                          BigDecimal saldoAnterior, BigDecimal entradas, BigDecimal salidas, BigDecimal saldoFinal,
                          List<Fila> movimientos, boolean truncado) {}

    @Transactional(readOnly = true)
    public Reporte reporte(Long cia, Long articuloId, Long almacenId, LocalDate desde, LocalDate hasta) {
        var p = new MapSqlParameterSource()
                .addValue("cia", cia, Types.BIGINT).addValue("articulo", articuloId, Types.BIGINT)
                .addValue("almacen", almacenId, Types.BIGINT)
                .addValue("desde", desde == null ? null : Date.valueOf(desde), Types.DATE)
                .addValue("hasta", hasta == null ? null : Date.valueOf(hasta), Types.DATE)
                .addValue("max", MAX_FILAS + 1, Types.INTEGER);
        var art = jdbc.queryForMap("""
                select coalesce(a.codigo_barra, a.referencia) codigo, a.nombre, u.nombre unidad, a.costo_promedio,
                       (select al.nombre from almacen al where al.id = :almacen) almacen
                  from articulo a join unidad u on u.id = a.unidad_id where a.id = :articulo and a.compania_id = :cia""", p);
        BigDecimal anterior = desde == null ? BigDecimal.ZERO : jdbc.queryForObject("""
                select coalesce(sum(m.cantidad), 0) from inventario_movimiento m
                 where m.articulo_id = :articulo and m.compania_id = :cia and (cast(:almacen as bigint) is null or m.almacen_id = :almacen)
                   and (m.fecha at time zone 'America/Santo_Domingo')::date < cast(:desde as date)""", p, BigDecimal.class);
        p.addValue("anterior", anterior, Types.NUMERIC);
        List<Fila> filas = jdbc.query("""
                select m.id, m.fecha, m.tipo, m.documento_tipo, m.documento_id, m.almacen_id, al.nombre almacen,
                       m.cantidad, m.costo_unitario, m.usuario,
                       cast(:anterior as numeric) + sum(m.cantidad) over (order by m.fecha, m.id) saldo
                  from inventario_movimiento m join almacen al on al.id = m.almacen_id
                 where m.articulo_id = :articulo and m.compania_id = :cia and (cast(:almacen as bigint) is null or m.almacen_id = :almacen)
                   and (cast(:desde as date) is null or (m.fecha at time zone 'America/Santo_Domingo')::date >= cast(:desde as date))
                   and (cast(:hasta as date) is null or (m.fecha at time zone 'America/Santo_Domingo')::date <= cast(:hasta as date))
                 order by m.fecha, m.id
                 limit :max""", p, (rs, i) -> new Fila(rs.getLong("id"), rs.getTimestamp("fecha").toInstant(), rs.getString("tipo"),
                rs.getString("documento_tipo"), rs.getLong("documento_id"), rs.getLong("almacen_id"), rs.getString("almacen"),
                rs.getBigDecimal("cantidad"), rs.getBigDecimal("costo_unitario"), rs.getBigDecimal("saldo"), rs.getString("usuario")));
        boolean truncado = filas.size() > MAX_FILAS;
        if (truncado) filas = filas.subList(0, MAX_FILAS);
        BigDecimal entradas = filas.stream().map(Fila::cantidad).filter(c -> c.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal salidas = filas.stream().map(Fila::cantidad).filter(c -> c.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add).negate();
        return new Reporte(articuloId, (String) art.get("codigo"), (String) art.get("nombre"), (String) art.get("unidad"),
                (BigDecimal) art.get("costo_promedio"), desde, hasta, almacenId, (String) art.get("almacen"),
                anterior, entradas, salidas, anterior.add(entradas).subtract(salidas), filas, truncado);
    }
}
