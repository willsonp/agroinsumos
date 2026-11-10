package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.reporte.Reporte.Columna;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.agroinsumos.backend.reporte.Reporte.Filtro.*;
import static com.agroinsumos.backend.reporte.Reporte.Grupo.COMPRAS;

/** Compras y pagos a suplidores (montos en RD$; las compras en otra moneda, a su tasa). */
@Component
class ReportesCompras extends CatalogoBase {

    ReportesCompras(NamedParameterJdbcTemplate jdbc) { super(jdbc); }

    @Override
    public List<Reporte> reportes() {
        return List.of(
                reporte("compras-por-suplidor", "Compras resumidas por suplidor", COMPRAS,
                        "Compras del período por suplidor, notas de crédito, lo pagado y lo pendiente.", "RptEstadosTodosSuplidorRangoFecha",
                        List.of(RANGO_FECHAS, PROVEEDOR),
                        List.of(Columna.texto("suplidor", "Suplidor"), Columna.texto("rnc", "RNC / Cédula"), Columna.entero("compras", "Compras"),
                                Columna.moneda("subtotal", "Sin ITBIS"), Columna.moneda("itbis", "ITBIS"), Columna.moneda("total", "Total"),
                                Columna.moneda("retenciones", "Retenciones"), Columna.moneda("pendiente", "Pendiente")),
                        """
                        select p.nombre suplidor, p.rnc_cedula rnc, count(*) compras, sum(c.total - c.total_itbis) subtotal, sum(c.total_itbis) itbis,
                               sum(c.total) total, sum(c.itbis_retenido + c.isr_retenido) retenciones, sum(c.balance) pendiente
                          from compra c join proveedor p on p.id = c.proveedor_id
                         where c.compania_id = :cia and c.estado <> 'ANULADA' and c.fecha between :desde and :hasta
                           and (:proveedor is null or c.proveedor_id = :proveedor)
                         group by p.id, p.nombre, p.rnc_cedula order by total desc"""),

                reporte("compras-detalle", "Compras detalladas", COMPRAS,
                        "Facturas de suplidores del período con su NCF, condición, moneda y balance.", "RptCompraCompleta",
                        List.of(RANGO_FECHAS, PROVEEDOR),
                        List.of(Columna.texto("numero", "No."), Columna.fecha("fecha", "Fecha"), Columna.texto("suplidor", "Suplidor"),
                                Columna.texto("ncf", "NCF"), Columna.texto("condicion", "Condición"), Columna.texto("moneda", "Moneda"),
                                Columna.moneda("subtotal", "Sin ITBIS"), Columna.moneda("itbis", "ITBIS"), Columna.moneda("total", "Total RD$"),
                                Columna.moneda("balance", "Pendiente RD$"), Columna.texto("estado", "Estado")),
                        """
                        select c.numero::text numero, c.fecha, p.nombre suplidor, coalesce(c.ncf_proveedor, c.encf_compra) ncf, c.condicion,
                               case when c.moneda = 'DOP' then 'DOP' else c.moneda || ' ' || c.total_moneda || ' a ' || c.tasa_cambio end moneda,
                               c.total - c.total_itbis subtotal, c.total_itbis itbis, c.total, c.balance, c.estado
                          from compra c join proveedor p on p.id = c.proveedor_id
                         where c.compania_id = :cia and c.fecha between :desde and :hasta and (:proveedor is null or c.proveedor_id = :proveedor)
                         order by c.fecha, c.numero"""),

                reporte("pagos-suplidores", "Pagos a suplidores", COMPRAS,
                        "Pagos del período con sus formas de pago; la diferencia cambiaria es la de los pagos en otra moneda.", "RptPagosPorFecha",
                        List.of(RANGO_FECHAS, PROVEEDOR),
                        List.of(Columna.texto("numero", "No."), Columna.fecha("fecha", "Fecha"), Columna.texto("suplidor", "Suplidor"),
                                Columna.texto("formas", "Formas"), Columna.texto("moneda", "Moneda"), Columna.moneda("pesos", "Pagado RD$"),
                                Columna.moneda("diferencia", "Dif. cambiaria"), Columna.texto("estado", "Estado")),
                        """
                        select pp.numero::text numero, pp.fecha, p.nombre suplidor,
                               (select string_agg(distinct lower(f.forma_pago) || coalesce(' ' || f.referencia, ''), ', ')
                                  from pago_proveedor_forma f where f.pago_id = pp.id) formas,
                               case when pp.moneda = 'DOP' then 'DOP' else pp.moneda || ' ' || pp.total || ' a ' || pp.tasa_cambio end moneda,
                               case when pp.estado = 'EMITIDO' then round(pp.total * pp.tasa_cambio, 2) else 0 end pesos,
                               case when pp.estado = 'EMITIDO' then pp.diferencia_cambiaria else 0 end diferencia, pp.estado
                          from pago_proveedor pp join proveedor p on p.id = pp.proveedor_id
                         where pp.compania_id = :cia and pp.fecha between :desde and :hasta and (:proveedor is null or pp.proveedor_id = :proveedor)
                         order by pp.fecha, pp.numero"""));
    }
}
