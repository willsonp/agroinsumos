package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Factura: Dr CxC cliente (total) / Cr Ingresos por departamento (neto) / Cr ITBIS por pagar;
 * y Dr Costo de ventas / Cr Inventario por el costo de los bienes. La factura de contado también pasa
 * por CxC: el recibo la salda contra caja/banco.
 */
@Component
public class FuenteVentas extends FuenteBase {

    private record Linea(Long docId, BigDecimal importe, BigDecimal costo, String tipo, String depto,
                         String ctaIngreso, String ctaCosto, String ctaInventario) {}

    public FuenteVentas(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "FACTURA"; }
    @Override public String descripcion() { return "Facturas de venta"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        record Cab(DocumentoContable doc, BigDecimal total, BigDecimal itbis, Long clienteId, String ctaCliente) {}
        List<Cab> cabs = jdbc.query("""
                select f.id, f.numero, f.fecha, f.total, f.total_itbis, f.cliente_id, f.cliente_nombre,
                       coalesce(f.encf, f.ncf_legacy) ncf, c.cuenta_contable
                  from factura f join cliente c on c.id = f.cliente_id
                 where f.compania_id = ? and f.fecha between ? and ? and f.estado <> 'ANULADA' and %s
                 order by f.fecha, f.id""".formatted(sinAsiento("f")),
                (rs, i) -> new Cab(new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                        "Factura " + rs.getLong(2) + " " + nz(rs.getString(8)) + " - " + rs.getString(7)),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getLong(6), rs.getString(9)),
                cia, Date.valueOf(desde), Date.valueOf(hasta));
        if (cabs.isEmpty()) return List.of();
        var lineas = agrupar(jdbc.query("""
                select fl.factura_id, fl.importe, fl.cantidad * fl.costo_unitario, a.tipo, d.nombre,
                       d.cuenta_ingreso, d.cuenta_costo, d.cuenta_inventario
                  from factura_linea fl join articulo a on a.id = fl.articulo_id join departamento d on d.id = a.departamento_id
                 where fl.factura_id = any(?) order by fl.factura_id, fl.linea""",
                (rs, i) -> new Linea(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)),
                (Object) cabs.stream().map(c -> c.doc().origenId()).toArray(Long[]::new)), Linea::docId);

        return cabs.stream().map(c -> {
            var doc = c.doc();
            doc.add(Partida.debe(c.ctaCliente(), "cliente", ClaveContable.CXC_CLIENTES, c.total(), "Cuentas por cobrar").tercero("C", c.clienteId()));
            var ls = lineas.getOrDefault(doc.origenId(), List.of());
            var ingresos = repartir(c.total().subtract(c.itbis()), ls.stream().map(Linea::importe).toList());
            for (int i = 0; i < ls.size(); i++) {
                var l = ls.get(i);
                doc.add(Partida.haber(l.ctaIngreso(), "departamento " + l.depto(), ClaveContable.INGRESOS_VENTAS, ingresos.get(i), "Ventas " + l.depto()));
                if ("B".equals(l.tipo())) {
                    BigDecimal costo = r2(l.costo());
                    doc.add(Partida.debe(l.ctaCosto(), "departamento " + l.depto(), ClaveContable.COSTO_VENTAS, costo, "Costo de ventas " + l.depto()));
                    doc.add(Partida.haber(l.ctaInventario(), "departamento " + l.depto(), ClaveContable.INVENTARIO, costo, "Salida de inventario " + l.depto()));
                }
            }
            doc.add(Partida.haber(ClaveContable.ITBIS_POR_PAGAR, c.itbis(), "ITBIS facturado"));
            return doc;
        }).toList();
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return anulados(cia, "factura", "x.estado = 'ANULADA'"); }
}
