package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Caja chica. Gasto: Dr Gasto (cuenta del vale o gastos de caja) / Dr ITBIS adelantado · Cr Caja chica.
 * Apertura y reposición: Dr Caja chica · Cr Banco (cheque, transferencia) o Caja (efectivo).
 */
@Component
public class FuenteCajaChica extends FuenteBase {

    public FuenteCajaChica(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "CAJA_CHICA"; }
    @Override public String descripcion() { return "Caja chica"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                select m.id, m.numero, m.fecha, m.tipo, m.concepto, m.monto, m.itbis, m.cuenta_gasto, m.forma,
                       f.nombre, f.cuenta_contable, cb.cuenta_contable, cb.nombre, m.beneficiario
                  from caja_chica_movimiento m join caja_chica f on f.id = m.caja_chica_id
                  left join cuenta_bancaria cb on cb.id = m.cuenta_bancaria_id
                 where m.compania_id = ? and m.fecha between ? and ? and m.estado = 'REGISTRADO' and %s
                 order by m.fecha, m.id""".formatted(sinAsiento("m")),
                (rs, i) -> {
                    String tipo = rs.getString(4), fondo = rs.getString(10), ctaFondo = rs.getString(11);
                    BigDecimal monto = rs.getBigDecimal(6), itbis = rs.getBigDecimal(7);
                    var doc = new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                            fondo + " · " + tipo.toLowerCase() + " " + rs.getLong(2) + ": " + rs.getString(5)
                                    + (rs.getString(14) == null ? "" : " (" + rs.getString(14) + ")"));
                    if ("GASTO".equals(tipo)) {
                        doc.add(Partida.debe(rs.getString(8), "cuenta del gasto", ClaveContable.GASTOS_CAJA, monto.subtract(itbis), rs.getString(5)));
                        doc.add(Partida.debe(ClaveContable.ITBIS_ADELANTADO, itbis, "ITBIS en gastos"));
                        doc.add(Partida.haber(ctaFondo, "caja chica " + fondo, ClaveContable.CAJA_CHICA, monto, "Pagado con caja chica"));
                    } else {
                        doc.add(Partida.debe(ctaFondo, "caja chica " + fondo, ClaveContable.CAJA_CHICA, monto, rs.getString(5)));
                        if ("EFECTIVO".equals(rs.getString(9))) doc.add(Partida.haber(ClaveContable.CAJA, monto, "Efectivo de la caja"));
                        else doc.add(Partida.haber(rs.getString(12), "cuenta bancaria " + nz(rs.getString(13)), ClaveContable.BANCO, monto,
                                "CHEQUE".equals(rs.getString(9)) ? "Cheque" : "Transferencia"));
                    }
                    return doc;
                }, cia, Date.valueOf(desde), Date.valueOf(hasta));
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return anulados(cia, "caja_chica_movimiento", "x.estado = 'ANULADO'"); }
}
