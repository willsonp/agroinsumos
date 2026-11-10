package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Abonos de apartados: anticipos del cliente. Abono: Dr Caja / Tarjetas / Banco · Cr CxC del cliente (queda a favor);
 * devolución al cancelar: Dr CxC · Cr Caja. Al facturar el apartado, el recibo con saldo a favor neta la CxC.
 */
@Component
public class FuenteAbonosApartado extends FuenteBase {

    public FuenteAbonosApartado(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "ABONO_APARTADO"; }
    @Override public String descripcion() { return "Abonos de apartados"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                select x.id, x.fecha, x.tipo, x.forma_pago, x.monto, a.numero, a.cliente_id, c.nombre, c.cuenta_contable,
                       (select cb.cuenta_contable from movimiento_bancario m join cuenta_bancaria cb on cb.id = m.cuenta_id
                         where m.origen_tipo = 'ABONO_APARTADO' and m.origen_id = x.id limit 1),
                       (select cb.nombre from movimiento_bancario m join cuenta_bancaria cb on cb.id = m.cuenta_id
                         where m.origen_tipo = 'ABONO_APARTADO' and m.origen_id = x.id limit 1)
                  from apartado_abono x join apartado a on a.id = x.apartado_id join cliente c on c.id = a.cliente_id
                 where x.compania_id = ? and x.fecha between ? and ? and %s
                 order by x.fecha, x.id""".formatted(sinAsiento("x")),
                (rs, i) -> {
                    boolean abono = "ABONO".equals(rs.getString(3));
                    BigDecimal monto = rs.getBigDecimal(5);
                    var doc = new DocumentoContable(origen(), rs.getLong(1), rs.getDate(2).toLocalDate(),
                            (abono ? "Abono" : "Devolución") + " apartado " + rs.getLong(6) + " - " + rs.getString(8));
                    Partida cxc = abono
                            ? Partida.haber(rs.getString(9), "cliente", ClaveContable.CXC_CLIENTES, monto, "Anticipo del cliente")
                            : Partida.debe(rs.getString(9), "cliente", ClaveContable.CXC_CLIENTES, monto, "Devolución de anticipo");
                    Partida dinero = switch (rs.getString(4)) {
                        case "TARJETA" -> Partida.debe(ClaveContable.TARJETAS_POR_COBRAR, monto, "Abono con tarjeta");
                        case "TRANSFERENCIA" -> Partida.debe(rs.getString(10), "cuenta bancaria " + nz(rs.getString(11)), ClaveContable.BANCO,
                                monto, "Abono por transferencia");
                        default -> abono ? Partida.debe(ClaveContable.CAJA, monto, "Abono recibido")
                                : Partida.haber(ClaveContable.CAJA, monto, "Devolución en efectivo");
                    };
                    doc.add(dinero);
                    doc.add(cxc.tercero("C", rs.getLong(7)));
                    return doc;
                }, cia, Date.valueOf(desde), Date.valueOf(hasta));
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return List.of(); }
}
