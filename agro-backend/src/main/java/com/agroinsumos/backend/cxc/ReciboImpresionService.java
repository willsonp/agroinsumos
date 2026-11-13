package com.agroinsumos.backend.cxc;

import com.agroinsumos.backend.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Recibo de ingreso impreso (acuse de recibo para el cliente): qué documentos se pagaron o abonaron, cuánto quedó
 * debiendo en cada uno y su balance total después del cobro.
 */
@Service
@RequiredArgsConstructor
public class ReciboImpresionService {

    public record Documento(String tipo, long numero, String comprobante, BigDecimal monto, BigDecimal retenciones,
                            BigDecimal balanceDespues, boolean saldado) {}

    public record Pago(String forma, BigDecimal monto, String referencia) {}

    public record ReciboImpresion(Long id, long numero, LocalDate fecha, String estado, Long clienteId,
                                  String clienteNombre, String clienteRnc, String concepto, String atendio,
                                  BigDecimal totalAplicado, BigDecimal totalRetenciones, BigDecimal totalRecibido,
                                  BigDecimal devuelta, BigDecimal balanceCliente, List<Documento> documentos, List<Pago> pagos) {}

    private final JdbcTemplate jdbc;

    /** Lo que el cliente debe: facturas y notas de débito con balance (mismo criterio que los pendientes de Cobros). */
    BigDecimal balanceCliente(Long cia, Long clienteId) {
        return jdbc.queryForObject("""
                select coalesce((select sum(balance) from factura where compania_id = ? and cliente_id = ? and balance > 0
                                   and estado <> 'ANULADA'), 0)
                     + coalesce((select sum(balance) from nota_debito where compania_id = ? and cliente_id = ? and balance > 0), 0)""",
                BigDecimal.class, cia, clienteId, cia, clienteId);
    }

    @Transactional(readOnly = true)
    public ReciboImpresion impresion(Long cia, Long id) {
        var docs = jdbc.query("""
                select a.documento_tipo, coalesce(f.numero, n.numero) numero, coalesce(f.encf, f.ncf_legacy, n.encf) comprobante,
                       a.monto, a.retencion_itbis + a.retencion_isr retenciones, a.balance_despues
                  from recibo_aplicacion a
                  left join factura f on a.documento_tipo = 'FACTURA' and f.id = a.documento_id
                  left join nota_debito n on a.documento_tipo = 'NOTA_DEBITO' and n.id = a.documento_id
                 where a.recibo_id = ? order by a.id""", (rs, i) -> {
            BigDecimal bal = rs.getBigDecimal("balance_despues");
            return new Documento(rs.getString("documento_tipo"), rs.getLong("numero"), rs.getString("comprobante"),
                    rs.getBigDecimal("monto"), rs.getBigDecimal("retenciones"), bal, bal != null && bal.signum() == 0);
        }, id);
        var pagos = jdbc.query("select forma_pago, monto, referencia from recibo_pago where recibo_id = ? order by id",
                (rs, i) -> new Pago(rs.getString(1), rs.getBigDecimal(2), rs.getString(3)), id);
        return jdbc.query("""
                select r.id, r.numero, r.fecha, r.estado, r.cliente_id, c.nombre, c.rnc_cedula, r.concepto,
                       u.username, r.total_aplicado, r.total_retenciones, r.total_recibido, r.devuelta, r.balance_cliente_despues
                  from recibo r join cliente c on c.id = r.cliente_id left join usuario u on u.id = r.usuario_id
                 where r.id = ? and r.compania_id = ?""", rs -> {
            if (!rs.next()) throw new NotFoundException("Recibo", id);
            return new ReciboImpresion(rs.getLong("id"), rs.getLong("numero"), rs.getObject("fecha", LocalDate.class),
                    rs.getString("estado"), rs.getLong("cliente_id"),
                    rs.getString("nombre"), rs.getString("rnc_cedula"), rs.getString("concepto"), rs.getString("username"),
                    rs.getBigDecimal("total_aplicado"), rs.getBigDecimal("total_retenciones"), rs.getBigDecimal("total_recibido"),
                    rs.getBigDecimal("devuelta"), rs.getBigDecimal("balance_cliente_despues"), docs, pagos);
        }, id, cia);
    }
}
