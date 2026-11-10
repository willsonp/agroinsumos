package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Compra: Dr Inventario por departamento (bienes, incluye flete) o Gastos (servicios) / Dr ITBIS adelantado;
 * Cr CxP proveedor (a pagar) / Cr ITBIS e ISR retenidos por pagar. La de contado también pasa por CxP.
 */
@Component
public class FuenteCompras extends FuenteBase {

    private record Linea(Long docId, BigDecimal importe, String tipo, String depto, String ctaInventario) {}

    public FuenteCompras(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "COMPRA"; }
    @Override public String descripcion() { return "Compras"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        record Cab(DocumentoContable doc, BigDecimal total, BigDecimal itbis, BigDecimal retItbis, BigDecimal retIsr,
                   Long proveedorId, String ctaProveedor) {}
        List<Cab> cabs = jdbc.query("""
                select co.id, co.numero, co.fecha, co.total, co.total_itbis, co.itbis_retenido, co.isr_retenido, co.proveedor_id,
                       p.nombre, coalesce(co.ncf_proveedor, co.encf_compra), p.cuenta_contable
                  from compra co join proveedor p on p.id = co.proveedor_id
                 where co.compania_id = ? and co.fecha between ? and ? and co.estado <> 'ANULADA' and %s
                 order by co.fecha, co.id""".formatted(sinAsiento("co")),
                (rs, i) -> new Cab(new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                        "Compra " + rs.getLong(2) + " " + nz(rs.getString(10)) + " - " + rs.getString(9)),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getLong(8), rs.getString(11)),
                cia, Date.valueOf(desde), Date.valueOf(hasta));
        if (cabs.isEmpty()) return List.of();
        var lineas = agrupar(jdbc.query("""
                select cl.compra_id, cl.importe, a.tipo, d.nombre, d.cuenta_inventario
                  from compra_linea cl join articulo a on a.id = cl.articulo_id join departamento d on d.id = a.departamento_id
                 where cl.compra_id = any(?) order by cl.compra_id, cl.linea""",
                (rs, i) -> new Linea(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                (Object) cabs.stream().map(c -> c.doc().origenId()).toArray(Long[]::new)), Linea::docId);

        return cabs.stream().map(c -> {
            var doc = c.doc();
            var ls = lineas.getOrDefault(doc.origenId(), List.of());
            var montos = repartir(c.total().subtract(c.itbis()), ls.stream().map(Linea::importe).toList());
            for (int i = 0; i < ls.size(); i++) {
                var l = ls.get(i);
                if ("B".equals(l.tipo())) {
                    doc.add(Partida.debe(l.ctaInventario(), "departamento " + l.depto(), ClaveContable.INVENTARIO, montos.get(i), "Entrada de inventario " + l.depto()));
                } else {
                    doc.add(Partida.debe(ClaveContable.GASTOS_COMPRAS, montos.get(i), "Servicios " + l.depto()));
                }
            }
            doc.add(Partida.debe(ClaveContable.ITBIS_ADELANTADO, c.itbis(), "ITBIS en compras"));
            doc.add(Partida.haber(c.ctaProveedor(), "proveedor", ClaveContable.CXP_PROVEEDORES,
                    c.total().subtract(c.retItbis()).subtract(c.retIsr()), "Cuentas por pagar").tercero("P", c.proveedorId()));
            doc.add(Partida.haber(ClaveContable.ITBIS_RETENIDO_POR_PAGAR, c.retItbis(), "ITBIS retenido al suplidor"));
            doc.add(Partida.haber(ClaveContable.ISR_RETENIDO_POR_PAGAR, c.retIsr(), "ISR retenido al suplidor"));
            return doc;
        }).toList();
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return anulados(cia, "compra", "x.estado = 'ANULADA'"); }
}
