package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Nota de crédito por devolución (e-CF 34): Dr Devoluciones en ventas / Dr ITBIS por pagar / Cr CxC cliente;
 * si la mercancía reingresa: Dr Inventario / Cr Costo de ventas al costo de la factura.
 */
@Component
public class FuenteDevoluciones extends FuenteBase {

    private record Linea(Long docId, BigDecimal costo, boolean reingresa, String tipo, String depto, String ctaCosto, String ctaInventario) {}

    public FuenteDevoluciones(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "DEVOLUCION"; }
    @Override public String descripcion() { return "Devoluciones (notas de crédito)"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        record Cab(DocumentoContable doc, BigDecimal total, BigDecimal itbis, Long clienteId, String ctaCliente) {}
        List<Cab> cabs = jdbc.query("""
                select dv.id, dv.numero, dv.fecha, dv.total, dv.total_itbis, f.cliente_id, f.cliente_nombre, dv.encf, f.numero,
                       c.cuenta_contable
                  from devolucion dv join factura f on f.id = dv.factura_id join cliente c on c.id = f.cliente_id
                 where dv.compania_id = ? and dv.fecha between ? and ? and %s
                 order by dv.fecha, dv.id""".formatted(sinAsiento("dv")),
                (rs, i) -> new Cab(new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                        "Devolución " + rs.getLong(2) + " " + nz(rs.getString(8)) + " (factura " + rs.getLong(9) + ") - " + rs.getString(7)),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getLong(6), rs.getString(10)),
                cia, Date.valueOf(desde), Date.valueOf(hasta));
        if (cabs.isEmpty()) return List.of();
        var lineas = agrupar(jdbc.query("""
                select dl.devolucion_id, dl.cantidad * fl.costo_unitario, dl.reingresa_inventario, a.tipo, d.nombre,
                       d.cuenta_costo, d.cuenta_inventario
                  from devolucion_linea dl join factura_linea fl on fl.id = dl.factura_linea_id
                  join articulo a on a.id = fl.articulo_id join departamento d on d.id = a.departamento_id
                 where dl.devolucion_id = any(?) order by dl.devolucion_id, dl.id""",
                (rs, i) -> new Linea(rs.getLong(1), rs.getBigDecimal(2), rs.getBoolean(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)),
                (Object) cabs.stream().map(c -> c.doc().origenId()).toArray(Long[]::new)), Linea::docId);

        return cabs.stream().map(c -> {
            var doc = c.doc();
            doc.add(Partida.debe(ClaveContable.DEVOLUCIONES_VENTAS, c.total().subtract(c.itbis()), "Devolución en ventas"));
            doc.add(Partida.debe(ClaveContable.ITBIS_POR_PAGAR, c.itbis(), "ITBIS de la devolución"));
            doc.add(Partida.haber(c.ctaCliente(), "cliente", ClaveContable.CXC_CLIENTES, c.total(), "Cuentas por cobrar").tercero("C", c.clienteId()));
            for (var l : lineas.getOrDefault(doc.origenId(), List.of())) {
                if (!l.reingresa() || !"B".equals(l.tipo())) continue;
                BigDecimal costo = r2(l.costo());
                doc.add(Partida.debe(l.ctaInventario(), "departamento " + l.depto(), ClaveContable.INVENTARIO, costo, "Reingreso de inventario " + l.depto()));
                doc.add(Partida.haber(l.ctaCosto(), "departamento " + l.depto(), ClaveContable.COSTO_VENTAS, costo, "Reverso costo de ventas " + l.depto()));
            }
            return doc;
        }).toList();
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return List.of(); }
}
