package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.reporte.Reporte.Columna;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.agroinsumos.backend.reporte.Reporte.Filtro.*;
import static com.agroinsumos.backend.reporte.Reporte.Grupo.VENTAS;

/**
 * Ventas, utilidad y comisiones. Montos en RD$. La venta neta descuenta las devoluciones (notas de crédito);
 * la utilidad usa el costo promedio al momento de vender y descuenta lo devuelto.
 */
@Component
class ReportesVentas extends CatalogoBase {

    ReportesVentas(NamedParameterJdbcTemplate jdbc) { super(jdbc); }

    /** Cantidad, venta y costo netos de devoluciones por línea de factura. */
    private static final String LINEAS_NETAS = """
            select fl.articulo_id, f.cliente_id, f.vendedor_id,
                   fl.cantidad - fl.cantidad_devuelta cantidad,
                   round(fl.importe * (fl.cantidad - fl.cantidad_devuelta) / fl.cantidad, 2) venta,
                   round((fl.cantidad - fl.cantidad_devuelta) * fl.costo_unitario, 2) costo
              from factura_linea fl join factura f on f.id = fl.factura_id
             where f.compania_id = :cia and f.fecha between :desde and :hasta and %s
               and (:cliente is null or f.cliente_id = :cliente) and (:vendedor is null or f.vendedor_id = :vendedor)
            """.formatted(FACTURA_VALIDA);

    @Override
    public List<Reporte> reportes() {
        return List.of(
                reporte("ventas-por-fecha", "Ventas resumidas por fecha", VENTAS,
                        "Facturado por día (contado y crédito), devoluciones del día y venta neta.", "RptVentasResumidasPorFecha",
                        List.of(RANGO_FECHAS, VENDEDOR),
                        List.of(Columna.fecha("fecha", "Fecha"), Columna.entero("facturas", "Facturas"), Columna.moneda("contado", "Contado"),
                                Columna.moneda("credito", "Crédito"), Columna.moneda("subtotal", "Sin ITBIS"), Columna.moneda("itbis", "ITBIS"),
                                Columna.moneda("total", "Facturado"), Columna.moneda("devoluciones", "Devoluciones"), Columna.moneda("neto", "Venta neta")),
                        """
                        with dias as (
                          select f.fecha, count(*) facturas,
                                 sum(case when f.condicion = 'CONTADO' then f.total else 0 end) contado,
                                 sum(case when f.condicion = 'CREDITO' then f.total else 0 end) credito,
                                 sum(f.total - f.total_itbis) subtotal, sum(f.total_itbis) itbis, sum(f.total) total
                            from factura f
                           where f.compania_id = :cia and f.fecha between :desde and :hasta and %s
                             and (:vendedor is null or f.vendedor_id = :vendedor)
                           group by f.fecha),
                        dev as (
                          select d.fecha, sum(d.total) devoluciones from devolucion d join factura f on f.id = d.factura_id
                           where d.compania_id = :cia and d.fecha between :desde and :hasta and (:vendedor is null or f.vendedor_id = :vendedor)
                           group by d.fecha)
                        select coalesce(dias.fecha, dev.fecha) fecha, coalesce(facturas, 0) facturas, coalesce(contado, 0) contado,
                               coalesce(credito, 0) credito, coalesce(subtotal, 0) subtotal, coalesce(itbis, 0) itbis, coalesce(total, 0) total,
                               coalesce(devoluciones, 0) devoluciones, coalesce(total, 0) - coalesce(devoluciones, 0) neto
                          from dias full join dev on dev.fecha = dias.fecha order by 1""".formatted(FACTURA_VALIDA)),

                reporte("ventas-detalle", "Ventas detalladas (facturas)", VENTAS,
                        "Facturas emitidas en el período con su comprobante, condición y balance pendiente.", "RptVentasDetalle",
                        List.of(RANGO_FECHAS, CLIENTE, VENDEDOR),
                        List.of(Columna.texto("numero", "No."), Columna.fecha("fecha", "Fecha"), Columna.texto("comprobante", "e-NCF / NCF"),
                                Columna.texto("cliente", "Cliente"), Columna.texto("vendedor", "Vendedor"), Columna.texto("condicion", "Condición"),
                                Columna.moneda("subtotal", "Sin ITBIS"), Columna.moneda("itbis", "ITBIS"), Columna.moneda("total", "Total"),
                                Columna.moneda("balance", "Pendiente"), Columna.texto("estado", "Estado")),
                        """
                        select f.numero::text numero, f.fecha, coalesce(f.encf, f.ncf_legacy) comprobante, f.cliente_nombre cliente,
                               v.nombre vendedor, f.condicion, f.total - f.total_itbis subtotal, f.total_itbis itbis, f.total, f.balance, f.estado
                          from factura f left join vendedor v on v.id = f.vendedor_id
                         where f.compania_id = :cia and f.fecha between :desde and :hasta and %s
                           and (:cliente is null or f.cliente_id = :cliente) and (:vendedor is null or f.vendedor_id = :vendedor)
                         order by f.fecha, f.numero""".formatted(FACTURA_VALIDA)),

                reporte("ventas-por-cliente", "Ventas resumidas por cliente", VENTAS,
                        "Lo facturado a cada cliente en el período, sus devoluciones y lo que aún debe de esas facturas.",
                        "RptVentasResumidasPorCliente", List.of(RANGO_FECHAS, VENDEDOR),
                        List.of(Columna.texto("cliente", "Cliente"), Columna.texto("rnc", "RNC / Cédula"), Columna.entero("facturas", "Facturas"),
                                Columna.moneda("total", "Facturado"), Columna.moneda("devoluciones", "Devoluciones"), Columna.moneda("neto", "Venta neta"),
                                Columna.moneda("pendiente", "Pendiente")),
                        """
                        select c.nombre cliente, c.rnc_cedula rnc, count(*) facturas, sum(f.total) total,
                               coalesce(sum((select sum(d.total) from devolucion d where d.factura_id = f.id)), 0) devoluciones,
                               sum(f.total) - coalesce(sum((select sum(d.total) from devolucion d where d.factura_id = f.id)), 0) neto,
                               sum(f.balance) pendiente
                          from factura f join cliente c on c.id = f.cliente_id
                         where f.compania_id = :cia and f.fecha between :desde and :hasta and %s
                           and (:vendedor is null or f.vendedor_id = :vendedor)
                         group by c.id, c.nombre, c.rnc_cedula order by neto desc""".formatted(FACTURA_VALIDA)),

                reporte("comisiones-vendedor", "Comisiones por vendedor", VENTAS,
                        "Venta sin ITBIS menos devoluciones de cada vendedor, por su porcentaje de comisión.", "RptComisionVendedor",
                        List.of(RANGO_FECHAS, VENDEDOR),
                        List.of(Columna.texto("vendedor", "Vendedor"), Columna.entero("facturas", "Facturas"),
                                Columna.moneda("venta", "Venta sin ITBIS"), Columna.moneda("devoluciones", "Devoluciones sin ITBIS"),
                                Columna.moneda("base", "Base comisión"), Columna.porcentaje("porcentaje", "% comisión"),
                                Columna.moneda("comision", "Comisión")),
                        """
                        select x.vendedor, x.facturas, x.venta, x.devoluciones, x.venta - x.devoluciones base, x.porcentaje,
                               round((x.venta - x.devoluciones) * x.porcentaje / 100, 2) comision
                          from (select coalesce(v.nombre, '(Sin vendedor)') vendedor, coalesce(v.comision, 0) porcentaje, count(*) facturas,
                                       sum(f.total - f.total_itbis) venta,
                                       coalesce(sum((select sum(d.total - d.total_itbis) from devolucion d where d.factura_id = f.id)), 0) devoluciones
                                  from factura f left join vendedor v on v.id = f.vendedor_id
                                 where f.compania_id = :cia and f.fecha between :desde and :hasta and %s
                                   and (:vendedor is null or f.vendedor_id = :vendedor)
                                 group by v.id, v.nombre, v.comision) x
                         order by comision desc""".formatted(FACTURA_VALIDA)),

                reporte("utilidad-por-articulo", "Ventas y utilidad por artículo", VENTAS,
                        "Cantidad vendida, venta sin ITBIS, costo, utilidad y margen de cada artículo (neto de devoluciones).",
                        "FrmConsultaUtilidad", List.of(RANGO_FECHAS, DEPARTAMENTO, CLIENTE, VENDEDOR),
                        List.of(Columna.texto("articulo", "Artículo"), Columna.texto("codigo", "Código"), Columna.texto("departamento", "Departamento"),
                                Columna.numero("cantidad", "Cantidad"), Columna.moneda("venta", "Venta"), Columna.moneda("costo", "Costo"),
                                Columna.moneda("utilidad", "Utilidad"), Columna.porcentaje("margen", "Margen %")),
                        """
                        select a.nombre articulo, coalesce(a.codigo_barra, a.referencia) codigo, d.nombre departamento,
                               sum(l.cantidad) cantidad, sum(l.venta) venta, sum(l.costo) costo, sum(l.venta) - sum(l.costo) utilidad,
                               case when sum(l.venta) <> 0 then round((sum(l.venta) - sum(l.costo)) * 100 / sum(l.venta), 2) end margen
                          from (%s) l join articulo a on a.id = l.articulo_id join departamento d on d.id = a.departamento_id
                         where (:departamento is null or a.departamento_id = :departamento)
                         group by a.id, a.nombre, a.codigo_barra, a.referencia, d.nombre
                        having sum(l.cantidad) <> 0
                         order by utilidad desc""".formatted(LINEAS_NETAS)),

                reporte("utilidad-por-departamento", "Utilidad por departamento", VENTAS,
                        "Venta sin ITBIS, costo, utilidad y margen por departamento.", "RptUtilidad_Resumen",
                        List.of(RANGO_FECHAS, VENDEDOR),
                        List.of(Columna.texto("departamento", "Departamento"), Columna.moneda("venta", "Venta"), Columna.moneda("costo", "Costo"),
                                Columna.moneda("utilidad", "Utilidad"), Columna.porcentaje("margen", "Margen %")),
                        """
                        select d.nombre departamento, sum(l.venta) venta, sum(l.costo) costo, sum(l.venta) - sum(l.costo) utilidad,
                               case when sum(l.venta) <> 0 then round((sum(l.venta) - sum(l.costo)) * 100 / sum(l.venta), 2) end margen
                          from (%s) l join articulo a on a.id = l.articulo_id join departamento d on d.id = a.departamento_id
                         group by d.id, d.nombre order by utilidad desc""".formatted(LINEAS_NETAS)),

                reporte("devoluciones", "Devoluciones (notas de crédito)", VENTAS,
                        "Notas de crédito emitidas en el período.", "RptResumenDevolucion", List.of(RANGO_FECHAS, CLIENTE),
                        List.of(Columna.texto("numero", "No."), Columna.fecha("fecha", "Fecha"), Columna.texto("encf", "e-NCF"),
                                Columna.texto("factura", "Factura"), Columna.texto("cliente", "Cliente"), Columna.texto("motivo", "Motivo"),
                                Columna.moneda("itbis", "ITBIS"), Columna.moneda("total", "Total")),
                        """
                        select d.numero::text numero, d.fecha, d.encf, f.numero::text factura, f.cliente_nombre cliente, d.motivo,
                               d.total_itbis itbis, d.total
                          from devolucion d join factura f on f.id = d.factura_id
                         where d.compania_id = :cia and d.fecha between :desde and :hasta and (:cliente is null or f.cliente_id = :cliente)
                         order by d.fecha, d.numero"""));
    }
}
