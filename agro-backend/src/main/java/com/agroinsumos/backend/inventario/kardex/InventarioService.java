package com.agroinsumos.backend.inventario.kardex;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.inventario.articulo.Articulo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Único punto que modifica existencias (SRP). Lo usan Facturación, Devoluciones, Compras y
 * Documentos de inventario. Siempre dentro de la transacción del documento que origina el movimiento.
 */
@Service
@RequiredArgsConstructor
public class InventarioService {

    private final JdbcTemplate jdbc;
    private final MovimientoInventarioRepository movimientos;
    private final LoteService lotes;

    @Value("${app.inventario.permitir-negativo:false}")
    private boolean permitirNegativo;

    /**
     * @param lote       solo en entradas de compra de artículos con vencimiento: número y fecha del lote
     * @param referencia documento original cuando el movimiento lo revierte (p.ej. la factura de una devolución)
     */
    public record Movimiento(Articulo articulo, Long almacenId, TipoMovimiento tipo, BigDecimal cantidad,
                             BigDecimal costoUnitario, String documentoTipo, Long documentoId,
                             LoteService.LoteEntrada lote, LoteService.Referencia referencia) {
        public Movimiento(Articulo articulo, Long almacenId, TipoMovimiento tipo, BigDecimal cantidad,
                          BigDecimal costoUnitario, String documentoTipo, Long documentoId) {
            this(articulo, almacenId, tipo, cantidad, costoUnitario, documentoTipo, documentoId, null, null);
        }
    }

    /**
     * Aplica el movimiento: bloquea la fila de existencia (UPSERT + FOR UPDATE implícito),
     * valida que no quede negativa, actualiza costo promedio en entradas con costo y escribe el kardex.
     * @return existencia final en el almacén
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal mover(Movimiento m) {
        Articulo a = m.articulo();
        if (!a.inventariable()) return BigDecimal.ZERO; // los servicios no mueven inventario
        if (m.cantidad().signum() <= 0) throw new IllegalArgumentException("La cantidad del movimiento debe ser positiva");
        BigDecimal delta = m.tipo().entrada() ? m.cantidad() : m.cantidad().negate();

        BigDecimal anterior = jdbc.queryForObject("""
                insert into existencia(articulo_id, almacen_id, cantidad) values (?, ?, 0)
                on conflict (articulo_id, almacen_id) do update set cantidad = existencia.cantidad
                returning cantidad
                """, BigDecimal.class, a.getId(), m.almacenId());
        BigDecimal fin = anterior.add(delta);
        if (fin.signum() < 0 && !permitirNegativo) {
            throw new BusinessException("EXISTENCIA_INSUFICIENTE", "Existencia insuficiente de '" + a.getNombre()
                    + "': disponible " + anterior.stripTrailingZeros().toPlainString()
                    + ", requerido " + m.cantidad().stripTrailingZeros().toPlainString());
        }
        jdbc.update("update existencia set cantidad = ? where articulo_id = ? and almacen_id = ?", fin, a.getId(), m.almacenId());

        BigDecimal costo = m.costoUnitario() != null ? m.costoUnitario() : a.getCostoPromedio();
        if (m.tipo() == TipoMovimiento.COMPRA || m.tipo() == TipoMovimiento.SALDO_INICIAL) {
            actualizarCostoPromedio(a, m.cantidad(), costo);
        }

        var mov = new MovimientoInventario();
        mov.setCompaniaId(a.getCompaniaId());
        mov.setArticuloId(a.getId());
        mov.setAlmacenId(m.almacenId());
        mov.setTipo(m.tipo());
        mov.setCantidad(delta);
        mov.setCostoUnitario(costo);
        mov.setExistenciaFinal(fin);
        mov.setDocumentoTipo(m.documentoTipo());
        mov.setDocumentoId(m.documentoId());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        mov.setUsuario(auth == null ? "sistema" : auth.getName());
        movimientos.save(mov);
        lotes.aplicar(a, m, mov.getId());
        return fin;
    }

    /** Existencia actual en un almacén (sin bloquear). */
    public BigDecimal existencia(Long articuloId, Long almacenId) {
        var r = jdbc.queryForList("select cantidad from existencia where articulo_id = ? and almacen_id = ?",
                BigDecimal.class, articuloId, almacenId);
        return r.isEmpty() ? BigDecimal.ZERO : r.getFirst();
    }

    /** Costo promedio ponderado con la existencia total previa (todas las bodegas). */
    private void actualizarCostoPromedio(Articulo a, BigDecimal cantidad, BigDecimal costo) {
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(cantidad),0) from existencia where articulo_id = ?",
                BigDecimal.class, a.getId());
        BigDecimal previa = total.subtract(cantidad).max(BigDecimal.ZERO);
        BigDecimal valor = previa.multiply(a.getCostoPromedio()).add(cantidad.multiply(costo));
        BigDecimal base = previa.add(cantidad);
        if (base.signum() > 0) a.setCostoPromedio(valor.divide(base, 4, RoundingMode.HALF_UP));
        a.setCosto(costo);
    }
}
