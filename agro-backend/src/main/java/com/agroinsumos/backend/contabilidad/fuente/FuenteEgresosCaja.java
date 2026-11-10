package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** Gastos menores pagados por caja (egresos, e-CF 43): Dr Gastos / Dr ITBIS adelantado / Cr Caja. */
@Component
public class FuenteEgresosCaja extends FuenteBase {

    public FuenteEgresosCaja(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "EGRESO_CAJA"; }
    @Override public String descripcion() { return "Gastos pagados por caja"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                select e.id, e.numero, e.fecha, e.monto, e.itbis, e.concepto, e.beneficiario
                  from caja_egreso e
                 where e.compania_id = ? and e.fecha between ? and ? and %s
                 order by e.fecha, e.id""".formatted(sinAsiento("e")),
                (rs, i) -> {
                    var monto = rs.getBigDecimal(4);
                    var itbis = rs.getBigDecimal(5);
                    var doc = new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                            "Egreso de caja " + rs.getLong(2) + " - " + rs.getString(6) + (rs.getString(7) == null ? "" : " (" + rs.getString(7) + ")"));
                    doc.add(Partida.debe(ClaveContable.GASTOS_CAJA, monto.subtract(itbis), rs.getString(6)));
                    doc.add(Partida.debe(ClaveContable.ITBIS_ADELANTADO, itbis, "ITBIS en gastos"));
                    doc.add(Partida.haber(ClaveContable.CAJA, monto, "Salida de caja"));
                    return doc;
                }, cia, Date.valueOf(desde), Date.valueOf(hasta));
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return List.of(); }
}
