package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Notas de suplidores. Crédito: Dr CxP / Cr Inventario (departamento de lo devuelto, o la cuenta por defecto en
 * descuentos) / Cr ITBIS adelantado. Débito: Dr Gastos de compras / Dr ITBIS adelantado / Cr CxP.
 */
@Component
public class FuenteNotasProveedor extends FuenteBase {

    private record Linea(Long docId, BigDecimal importe, String depto, String ctaInventario) {}

    public FuenteNotasProveedor(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "NOTA_PROVEEDOR"; }
    @Override public String descripcion() { return "Notas de suplidores"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        record Cab(DocumentoContable doc, boolean credito, BigDecimal subtotal, BigDecimal itbis, BigDecimal total,
                   Long proveedorId, String ctaProveedor) {}
        List<Cab> cabs = jdbc.query("""
                select n.id, n.numero, n.fecha, n.tipo, n.subtotal, n.itbis, n.total, n.proveedor_id, p.nombre, n.ncf, n.concepto,
                       p.cuenta_contable, c.numero
                  from nota_proveedor n join proveedor p on p.id = n.proveedor_id join compra c on c.id = n.compra_id
                 where n.compania_id = ? and n.fecha between ? and ? and n.estado = 'EMITIDA' and %s
                 order by n.fecha, n.id""".formatted(sinAsiento("n")),
                (rs, i) -> {
                    boolean credito = "CREDITO".equals(rs.getString(4));
                    return new Cab(new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                            "Nota de " + (credito ? "crédito" : "débito") + " " + nz(rs.getString(10)) + " de " + rs.getString(9)
                                    + " (compra " + rs.getLong(13) + "): " + rs.getString(11)),
                            credito, rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getLong(8), rs.getString(12));
                }, cia, Date.valueOf(desde), Date.valueOf(hasta));
        if (cabs.isEmpty()) return List.of();
        var lineas = agrupar(jdbc.query("""
                select l.nota_id, l.importe, d.nombre, d.cuenta_inventario
                  from nota_proveedor_linea l join articulo a on a.id = l.articulo_id join departamento d on d.id = a.departamento_id
                 where l.nota_id = any(?) order by l.nota_id, l.id""",
                (rs, i) -> new Linea(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)),
                (Object) cabs.stream().map(c -> c.doc().origenId()).toArray(Long[]::new)), Linea::docId);

        return cabs.stream().map(c -> {
            var doc = c.doc();
            if (c.credito()) {
                doc.add(Partida.debe(c.ctaProveedor(), "proveedor", ClaveContable.CXP_PROVEEDORES, c.total(), "Nota de crédito del suplidor")
                        .tercero("P", c.proveedorId()));
                var ls = lineas.getOrDefault(doc.origenId(), List.of());
                if (ls.isEmpty()) {
                    doc.add(Partida.haber(ClaveContable.INVENTARIO, c.subtotal(), "Descuento / rebaja en compras"));
                } else {
                    var montos = repartir(c.subtotal(), ls.stream().map(Linea::importe).toList());
                    for (int i = 0; i < ls.size(); i++) {
                        var l = ls.get(i);
                        doc.add(Partida.haber(l.ctaInventario(), "departamento " + l.depto(), ClaveContable.INVENTARIO, montos.get(i),
                                "Devolución a suplidor " + l.depto()));
                    }
                }
                doc.add(Partida.haber(ClaveContable.ITBIS_ADELANTADO, c.itbis(), "ITBIS de la nota de crédito"));
            } else {
                doc.add(Partida.debe(ClaveContable.GASTOS_COMPRAS, c.subtotal(), "Cargo del suplidor"));
                doc.add(Partida.debe(ClaveContable.ITBIS_ADELANTADO, c.itbis(), "ITBIS de la nota de débito"));
                doc.add(Partida.haber(c.ctaProveedor(), "proveedor", ClaveContable.CXP_PROVEEDORES, c.total(), "Nota de débito del suplidor")
                        .tercero("P", c.proveedorId()));
            }
            return doc;
        }).toList();
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return anulados(cia, "nota_proveedor", "x.estado = 'ANULADA'"); }
}
