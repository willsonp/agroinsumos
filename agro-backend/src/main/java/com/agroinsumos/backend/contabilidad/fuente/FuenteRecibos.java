package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Recibo de ingreso: Dr Caja (efectivo neto de devuelta, cheques) / Tarjetas por cobrar / Banco (transferencias)
 * / CxC (saldo a favor aplicado) / Retenciones que nos hicieron; Cr CxC cliente por lo abonado.
 */
@Component
public class FuenteRecibos extends FuenteBase {

    private record Pago(Long docId, String forma, BigDecimal monto, String ctaBanco, String banco) {}

    public FuenteRecibos(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "RECIBO"; }
    @Override public String descripcion() { return "Recibos de cobro"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        record Cab(DocumentoContable doc, BigDecimal aplicado, BigDecimal devuelta, BigDecimal retItbis, BigDecimal retIsr,
                   Long clienteId, String ctaCliente) {}
        List<Cab> cabs = jdbc.query("""
                select r.id, r.numero, r.fecha, r.total_aplicado, r.devuelta, r.cliente_id, c.nombre, c.cuenta_contable,
                       coalesce((select sum(a.retencion_itbis) from recibo_aplicacion a where a.recibo_id = r.id), 0),
                       coalesce((select sum(a.retencion_isr) from recibo_aplicacion a where a.recibo_id = r.id), 0)
                  from recibo r join cliente c on c.id = r.cliente_id
                 where r.compania_id = ? and r.fecha between ? and ? and r.estado = 'EMITIDO' and %s
                 order by r.fecha, r.id""".formatted(sinAsiento("r")),
                (rs, i) -> new Cab(new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                        "Recibo " + rs.getLong(2) + " - " + rs.getString(7)),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getLong(6), rs.getString(8)),
                cia, Date.valueOf(desde), Date.valueOf(hasta));
        if (cabs.isEmpty()) return List.of();
        var pagos = agrupar(jdbc.query("""
                select rp.recibo_id, rp.forma_pago, rp.monto, cb.cuenta_contable, cb.nombre
                  from recibo_pago rp left join cuenta_bancaria cb on cb.id = rp.cuenta_bancaria_id
                 where rp.recibo_id = any(?) order by rp.recibo_id, rp.id""",
                (rs, i) -> new Pago(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5)),
                (Object) cabs.stream().map(c -> c.doc().origenId()).toArray(Long[]::new)), Pago::docId);

        return cabs.stream().map(c -> {
            var doc = c.doc();
            BigDecimal efectivo = c.devuelta().negate();
            for (var p : pagos.getOrDefault(doc.origenId(), List.of())) {
                switch (p.forma()) {
                    case "EFECTIVO" -> efectivo = efectivo.add(p.monto());
                    case "CHEQUE" -> doc.add(Partida.debe(ClaveContable.CAJA, p.monto(), "Cheque recibido"));
                    case "TARJETA" -> doc.add(Partida.debe(ClaveContable.TARJETAS_POR_COBRAR, p.monto(), "Cobro con tarjeta"));
                    case "TRANSFERENCIA" -> doc.add(Partida.debe(p.ctaBanco(), "cuenta bancaria " + nz(p.banco()), ClaveContable.BANCO,
                            p.monto(), "Transferencia recibida"));
                    case "SALDO_A_FAVOR" -> doc.add(Partida.debe(c.ctaCliente(), "cliente", ClaveContable.CXC_CLIENTES, p.monto(),
                            "Aplicación de saldo a favor").tercero("C", c.clienteId()));
                    default -> throw new IllegalStateException("Forma de pago no contemplada: " + p.forma());
                }
            }
            doc.partidas().add(0, Partida.debe(ClaveContable.CAJA, efectivo, "Efectivo recibido"));
            doc.partidas().removeIf(p -> p.monto().signum() <= 0);
            doc.add(Partida.debe(ClaveContable.ITBIS_RETENIDO_POR_CLIENTES, c.retItbis(), "ITBIS retenido por el cliente"));
            doc.add(Partida.debe(ClaveContable.ISR_RETENIDO_POR_CLIENTES, c.retIsr(), "ISR retenido por el cliente"));
            doc.add(Partida.haber(c.ctaCliente(), "cliente", ClaveContable.CXC_CLIENTES,
                    c.aplicado().add(c.retItbis()).add(c.retIsr()), "Abono a cuentas por cobrar").tercero("C", c.clienteId()));
            return doc;
        }).toList();
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return anulados(cia, "recibo", "x.estado = 'ANULADO'"); }
}
