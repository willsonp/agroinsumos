package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** Pago a suplidor: Dr CxP proveedor / Cr Caja (efectivo) o Banco (cheque, transferencia, tarjeta). */
@Component
public class FuentePagosProveedor extends FuenteBase {

    private record Forma(Long docId, String forma, BigDecimal monto, String ctaBanco, String banco) {}

    public FuentePagosProveedor(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "PAGO_PROVEEDOR"; }
    @Override public String descripcion() { return "Pagos a suplidores"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        record Cab(DocumentoContable doc, BigDecimal total, Long proveedorId, String ctaProveedor) {}
        List<Cab> cabs = jdbc.query("""
                select pp.id, pp.numero, pp.fecha, pp.total, pp.proveedor_id, p.nombre, p.cuenta_contable
                  from pago_proveedor pp join proveedor p on p.id = pp.proveedor_id
                 where pp.compania_id = ? and pp.fecha between ? and ? and pp.estado = 'EMITIDO' and %s
                 order by pp.fecha, pp.id""".formatted(sinAsiento("pp")),
                (rs, i) -> new Cab(new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                        "Pago " + rs.getLong(2) + " a " + rs.getString(6)), rs.getBigDecimal(4), rs.getLong(5), rs.getString(7)),
                cia, Date.valueOf(desde), Date.valueOf(hasta));
        if (cabs.isEmpty()) return List.of();
        var formas = agrupar(jdbc.query("""
                select f.pago_id, f.forma_pago, f.monto, cb.cuenta_contable, cb.nombre
                  from pago_proveedor_forma f left join cuenta_bancaria cb on cb.id = f.cuenta_bancaria_id
                 where f.pago_id = any(?) order by f.pago_id, f.id""",
                (rs, i) -> new Forma(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5)),
                (Object) cabs.stream().map(c -> c.doc().origenId()).toArray(Long[]::new)), Forma::docId);

        return cabs.stream().map(c -> {
            var doc = c.doc();
            doc.add(Partida.debe(c.ctaProveedor(), "proveedor", ClaveContable.CXP_PROVEEDORES, c.total(), "Pago de cuentas por pagar")
                    .tercero("P", c.proveedorId()));
            for (var f : formas.getOrDefault(doc.origenId(), List.of())) {
                if ("EFECTIVO".equals(f.forma())) doc.add(Partida.haber(ClaveContable.CAJA, f.monto(), "Pago en efectivo"));
                else doc.add(Partida.haber(f.ctaBanco(), "cuenta bancaria " + nz(f.banco()), ClaveContable.BANCO, f.monto(),
                        "CHEQUE".equals(f.forma()) ? "Cheque emitido" : "Pago por " + f.forma().toLowerCase()));
            }
            return doc;
        }).toList();
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return anulados(cia, "pago_proveedor", "x.estado = 'ANULADO'"); }
}
