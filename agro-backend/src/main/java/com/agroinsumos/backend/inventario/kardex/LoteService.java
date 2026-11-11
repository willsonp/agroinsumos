package com.agroinsumos.backend.inventario.kardex;

import com.agroinsumos.backend.inventario.articulo.Articulo;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Existencia por lote y fecha de vencimiento (solo artículos con {@code maneja_vencimiento}). La invoca
 * {@link InventarioService#mover} en cada movimiento, así que cubre ventas, conduces, devoluciones, ajustes,
 * transferencias, notas de suplidor y anulaciones sin que cada módulo lo implemente.
 *
 * <ul>
 *   <li><b>Entrada con lote</b> (compra): suma al lote (número + fecha) en el almacén.</li>
 *   <li><b>Entrada que revierte una salida</b> (anulación de conduce, devolución de la factura, transferencia):
 *       vuelve a los mismos lotes que salieron con ese documento o con el documento de referencia; en una
 *       transferencia se recrean en el almacén destino con el mismo número y fecha.</li>
 *   <li><b>Salida que revierte una entrada</b> (anulación de compra, devolución al suplidor): sale primero de los
 *       lotes que entraron con ese documento.</li>
 *   <li><b>Resto de salidas</b>: FEFO (el que vence primero). Las ventas saltan los lotes vencidos (se usan solo si
 *       no queda otro); los ajustes de salida sí los toman primero (es como se dan de baja los vencidos).</li>
 *   <li>Entrada sin dato de lote: va al lote "sin fecha" del almacén.</li>
 * </ul>
 * La existencia anterior a activar el control (sin lote) no se rastrea: las salidas toman de los lotes lo que haya.
 */
@Service
@RequiredArgsConstructor
public class LoteService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** Número y fecha de vencimiento del lote que entra (compras). */
    public record LoteEntrada(String numero, LocalDate fechaVencimiento) {
        public LoteEntrada {
            numero = numero == null ? "" : numero.trim().toUpperCase();
        }
    }

    /** Documento original al que se refiere el movimiento (p.ej. la factura de una devolución). */
    public record Referencia(String tipo, Long id) {}

    public record LoteResponse(Long id, Long almacenId, String almacen, String numero, LocalDate fechaVencimiento,
                               BigDecimal cantidadRecibida, BigDecimal existencia, Integer diasParaVencer) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void aplicar(Articulo a, InventarioService.Movimiento m, Long movimientoId) {
        if (!a.isManejaVencimiento()) return;
        if (m.tipo().entrada()) entrada(a, m, movimientoId);
        else salida(a, m, movimientoId);
    }

    public List<LoteResponse> lotes(Long articuloId, boolean soloConExistencia) {
        LocalDate hoy = LocalDate.now(clock);
        return jdbc.query("""
                select l.*, al.nombre almacen from lote l join almacen al on al.id = l.almacen_id
                 where l.articulo_id = ? and (not ? or l.existencia > 0)
                 order by l.fecha_vencimiento nulls last, l.id""", (rs, i) -> {
            Date f = rs.getDate("fecha_vencimiento");
            LocalDate vence = f == null ? null : f.toLocalDate();
            return new LoteResponse(rs.getLong("id"), rs.getLong("almacen_id"), rs.getString("almacen"), rs.getString("numero"), vence,
                    rs.getBigDecimal("cantidad_recibida"), rs.getBigDecimal("existencia"),
                    vence == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(hoy, vence));
        }, articuloId, soloConExistencia);
    }

    // ---------------------------------------------------------------- entradas

    private void entrada(Articulo a, InventarioService.Movimiento m, Long movId) {
        BigDecimal restante = m.cantidad();
        if (m.lote() != null) {
            sumar(a, m, movId, m.lote().numero(), m.lote().fechaVencimiento(), restante, true);
            return;
        }
        // Revierte salidas del mismo documento o del documento de referencia: vuelve a esos lotes (número + fecha)
        for (var fila : salidasPendientes(a.getId(), m)) {
            if (restante.signum() <= 0) break;
            BigDecimal n = restante.min((BigDecimal) fila.get("pendiente"));
            Date f = (Date) fila.get("fecha_vencimiento");
            sumar(a, m, movId, (String) fila.get("numero"), f == null ? null : f.toLocalDate(), n, false);
            restante = restante.subtract(n);
        }
        if (restante.signum() > 0) sumar(a, m, movId, "", null, restante, false);
    }

    /** Documento al que pertenecen los lotes a revertir: la referencia (p.ej. la factura) o el propio documento. */
    private static String refTipo(InventarioService.Movimiento m) { return m.referencia() == null ? m.documentoTipo() : m.referencia().tipo(); }
    private static Long refId(InventarioService.Movimiento m) { return m.referencia() == null ? m.documentoId() : m.referencia().id(); }

    /**
     * Lotes (número + fecha) que salieron con el documento de referencia y aún no han vuelto, del que vence antes.
     * Cuenta los movimientos del documento y los de cualquier otro documento que lo referencie (devoluciones previas).
     */
    private List<Map<String, Object>> salidasPendientes(Long articuloId, InventarioService.Movimiento m) {
        return jdbc.queryForList("""
                select l.numero, l.fecha_vencimiento, -sum(lm.cantidad) pendiente
                  from lote_movimiento lm join lote l on l.id = lm.lote_id
                 where l.articulo_id = ?
                   and ((lm.documento_tipo = ? and lm.documento_id = ?) or (lm.referencia_tipo = ? and lm.referencia_id = ?))
                 group by l.numero, l.fecha_vencimiento
                having sum(lm.cantidad) < 0
                 order by l.fecha_vencimiento nulls last""",
                articuloId, refTipo(m), refId(m), refTipo(m), refId(m));
    }

    private void sumar(Articulo a, InventarioService.Movimiento m, Long movId, String numero, LocalDate vence,
                       BigDecimal cantidad, boolean recibida) {
        Long loteId = jdbc.queryForObject("""
                insert into lote (compania_id, articulo_id, almacen_id, numero, fecha_vencimiento, cantidad_recibida, existencia)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (articulo_id, almacen_id, numero, fecha_vencimiento)
                do update set existencia = lote.existencia + excluded.existencia,
                              cantidad_recibida = lote.cantidad_recibida + excluded.cantidad_recibida
                returning id""", Long.class, a.getCompaniaId(), a.getId(), m.almacenId(), numero,
                vence == null ? null : Date.valueOf(vence), recibida ? cantidad : BigDecimal.ZERO, cantidad);
        registrar(loteId, movId, m, cantidad);
    }

    // ---------------------------------------------------------------- salidas

    private void salida(Articulo a, InventarioService.Movimiento m, Long movId) {
        BigDecimal restante = m.cantidad();
        // 1) Lotes que entraron con el documento de referencia y siguen ahí (anulación de compra, devolución al suplidor)
        var netos = jdbc.queryForList("""
                select l.id, sum(lm.cantidad) neto
                  from lote_movimiento lm join lote l on l.id = lm.lote_id
                 where l.articulo_id = ? and l.almacen_id = ?
                   and ((lm.documento_tipo = ? and lm.documento_id = ?) or (lm.referencia_tipo = ? and lm.referencia_id = ?))
                 group by l.id having sum(lm.cantidad) > 0""",
                a.getId(), m.almacenId(), refTipo(m), refId(m), refTipo(m), refId(m));
        if (!netos.isEmpty()) {
            Map<Long, BigDecimal> neto = new java.util.HashMap<>();
            netos.forEach(f -> neto.put(((Number) f.get("id")).longValue(), (BigDecimal) f.get("neto")));
            var bloqueados = jdbc.queryForList("""
                    select id, existencia from lote where id = any(?) order by fecha_vencimiento nulls last, id for update""",
                    (Object) neto.keySet().toArray(Long[]::new));
            bloqueados.forEach(f -> f.put("disponible", ((BigDecimal) f.get("existencia")).min(neto.get(((Number) f.get("id")).longValue()))));
            restante = tomar(m, movId, restante, bloqueados);
        }
        if (restante.signum() <= 0) return;
        // 2) FEFO; las ventas dejan para el final los lotes ya vencidos
        boolean venta = m.tipo() == TipoMovimiento.VENTA;
        tomar(m, movId, restante, jdbc.queryForList("""
                select l.id, l.existencia disponible from lote l
                 where l.articulo_id = ? and l.almacen_id = ? and l.existencia > 0
                 order by case when ? and l.fecha_vencimiento < ? then 1 else 0 end, l.fecha_vencimiento nulls last, l.id
                   for update""", a.getId(), m.almacenId(), venta, Date.valueOf(LocalDate.now(clock))));
    }

    private BigDecimal tomar(InventarioService.Movimiento m, Long movId, BigDecimal restante, List<Map<String, Object>> lotes) {
        for (var l : lotes) {
            if (restante.signum() <= 0) break;
            BigDecimal disponible = (BigDecimal) l.get("disponible");
            if (disponible == null || disponible.signum() <= 0) continue;
            BigDecimal n = restante.min(disponible);
            Long id = ((Number) l.get("id")).longValue();
            jdbc.update("update lote set existencia = existencia - ? where id = ?", n, id);
            registrar(id, movId, m, n.negate());
            restante = restante.subtract(n);
        }
        return restante;
    }

    private void registrar(Long loteId, Long movId, InventarioService.Movimiento m, BigDecimal cantidad) {
        jdbc.update("""
                insert into lote_movimiento (lote_id, movimiento_id, documento_tipo, documento_id, referencia_tipo, referencia_id, cantidad)
                values (?, ?, ?, ?, ?, ?, ?)""", loteId, movId, m.documentoTipo(), m.documentoId(),
                m.referencia() == null ? null : m.referencia().tipo(), m.referencia() == null ? null : m.referencia().id(), cantidad);
    }
}
