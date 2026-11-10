package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** Nota de débito a cliente (e-CF 33): Dr CxC / Cr Otros ingresos / Cr ITBIS por pagar. */
@Component
public class FuenteNotasDebito extends FuenteBase {

    public FuenteNotasDebito(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "NOTA_DEBITO"; }
    @Override public String descripcion() { return "Notas de débito a clientes"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                select nd.id, nd.numero, nd.fecha, nd.total, nd.total_itbis, nd.cliente_id, c.nombre, nd.encf, nd.concepto, c.cuenta_contable
                  from nota_debito nd join cliente c on c.id = nd.cliente_id
                 where nd.compania_id = ? and nd.fecha between ? and ? and %s
                 order by nd.fecha, nd.id""".formatted(sinAsiento("nd")),
                (rs, i) -> {
                    var doc = new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                            "Nota de débito " + rs.getLong(2) + " " + nz(rs.getString(8)) + " - " + rs.getString(7) + ": " + rs.getString(9));
                    var total = rs.getBigDecimal(4);
                    var itbis = rs.getBigDecimal(5);
                    doc.add(Partida.debe(rs.getString(10), "cliente", ClaveContable.CXC_CLIENTES, total, "Cuentas por cobrar").tercero("C", rs.getLong(6)));
                    doc.add(Partida.haber(ClaveContable.OTROS_INGRESOS, total.subtract(itbis), rs.getString(9)));
                    doc.add(Partida.haber(ClaveContable.ITBIS_POR_PAGAR, itbis, "ITBIS facturado"));
                    return doc;
                }, cia, Date.valueOf(desde), Date.valueOf(hasta));
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return List.of(); }
}
