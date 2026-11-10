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

    /**
     * Movimientos de CxP por suplidor en pesos: compras (lo que se le debe, sin las retenciones que van a la DGII), notas de
     * débito, notas de crédito y pagos (por los pesos que rebajan de las compras). Anuladas excluidas.
     */
    private static final String MOVIMIENTOS_CXP = """
            select c.proveedor_id, c.fecha, 1 prioridad, c.id orden,
                   'Compra ' || c.numero || coalesce(' · ' || coalesce(c.ncf_proveedor, c.encf_compra), '') documento,
                   c.fecha_vencimiento vence, c.total - c.itbis_retenido - c.isr_retenido cargo, 0::numeric abono
              from compra c where c.compania_id = :cia and c.estado <> 'ANULADA'
            union all
            select n.proveedor_id, n.fecha, 2, n.id, 'Nota de débito ' || n.numero || coalesce(' · ' || n.ncf, ''), null, n.total, 0
              from nota_proveedor n where n.compania_id = :cia and n.tipo = 'DEBITO' and n.estado = 'EMITIDA'
            union all
            select n.proveedor_id, n.fecha, 3, n.id, 'Nota de crédito ' || n.numero || coalesce(' · ' || n.ncf, ''), null, 0, n.total
              from nota_proveedor n where n.compania_id = :cia and n.tipo = 'CREDITO' and n.estado = 'EMITIDA'
            union all
            select pp.proveedor_id, pp.fecha, 4, pp.id,
                   'Pago ' || pp.numero || coalesce(' · ' || (select string_agg(lower(f.forma_pago) || coalesce(' ' || f.referencia, ''), ', ')
                                                               from pago_proveedor_forma f where f.pago_id = pp.id), ''),
                   null, 0, (select sum(a.monto_dop) from pago_proveedor_aplicacion a where a.pago_id = pp.id)
              from pago_proveedor pp where pp.compania_id = :cia and pp.estado = 'EMITIDO'""";

    @Override
    public List<Reporte> reportes() {
        return List.of(
                reporte("estado-cuenta-suplidor", "Estado de cuenta de un suplidor", COMPRAS,
                        "Saldo anterior, compras, notas de débito y crédito y pagos del período con el saldo corrido.", "RptEstadoCuentaCXP",
                        List.of(RANGO_FECHAS, PROVEEDOR_REQUERIDO),
                        List.of(Columna.fecha("fecha", "Fecha"), Columna.texto("documento", "Documento"), Columna.fecha("vence", "Vence"),
                                Columna.moneda("cargo", "Cargos"), Columna.moneda("abono", "Abonos"), Columna.monedaSinTotal("saldo", "Saldo")),
                        """
                        with mov as (select * from (%s) t where t.proveedor_id = :proveedor),
                             ant as (select coalesce(sum(cargo - abono), 0) s from mov where fecha < :desde)
                        select fecha, documento, vence, cargo, abono, saldo from (
                          select null::date fecha, 'Saldo anterior' documento, null::date vence, null::numeric cargo, null::numeric abono,
                                 (select s from ant) saldo, 0 grupo, 0 prioridad, 0::bigint orden
                          union all
                          select m.fecha, m.documento, m.vence, m.cargo, m.abono,
                                 (select s from ant) + sum(m.cargo - m.abono) over (order by m.fecha, m.prioridad, m.orden rows unbounded preceding),
                                 1, m.prioridad, m.orden
                            from mov m where m.fecha between :desde and :hasta) x
                         order by grupo, fecha, prioridad, orden""".formatted(MOVIMIENTOS_CXP)),

                reporte("saldos-suplidores", "Saldos de todos los suplidores", COMPRAS,
                        "Por suplidor: saldo anterior, cargos (compras y notas de débito), abonos (pagos y notas de crédito) y saldo al final.",
                        "RptEstadosTodosSuplidor", List.of(RANGO_FECHAS),
                        List.of(Columna.texto("suplidor", "Suplidor"), Columna.texto("rnc", "RNC / Cédula"), Columna.moneda("anterior", "Saldo anterior"),
                                Columna.moneda("cargos", "Cargos"), Columna.moneda("abonos", "Abonos"), Columna.moneda("saldo", "Saldo final")),
                        """
                        select p.nombre suplidor, p.rnc_cedula rnc,
                               coalesce(sum(m.cargo - m.abono) filter (where m.fecha < :desde), 0) anterior,
                               coalesce(sum(m.cargo) filter (where m.fecha between :desde and :hasta), 0) cargos,
                               coalesce(sum(m.abono) filter (where m.fecha between :desde and :hasta), 0) abonos,
                               coalesce(sum(m.cargo - m.abono) filter (where m.fecha <= :hasta), 0) saldo
                          from (%s) m join proveedor p on p.id = m.proveedor_id
                         group by p.id, p.nombre, p.rnc_cedula
                        having coalesce(sum(m.cargo - m.abono) filter (where m.fecha <= :hasta), 0) <> 0
                            or coalesce(sum(m.cargo + m.abono) filter (where m.fecha between :desde and :hasta), 0) <> 0
                         order by saldo desc""".formatted(MOVIMIENTOS_CXP)),


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
