package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.reporte.Reporte.Columna;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.agroinsumos.backend.reporte.Reporte.Filtro.*;
import static com.agroinsumos.backend.reporte.Reporte.Grupo.CXC_CAJA;

/** Cobros por forma de pago, facturas vencidas y egresos de caja. */
@Component
class ReportesCxcCaja extends CatalogoBase {

    ReportesCxcCaja(NamedParameterJdbcTemplate jdbc) { super(jdbc); }

    private static final String PAGOS_POR_RECIBO = """
            select rp.recibo_id,
                   sum(case when rp.forma_pago = 'EFECTIVO' then rp.monto else 0 end) efectivo,
                   sum(case when rp.forma_pago = 'TARJETA' then rp.monto else 0 end) tarjeta,
                   sum(case when rp.forma_pago = 'CHEQUE' then rp.monto else 0 end) cheque,
                   sum(case when rp.forma_pago = 'TRANSFERENCIA' then rp.monto else 0 end) transferencia,
                   sum(case when rp.forma_pago = 'SALDO_A_FAVOR' then rp.monto else 0 end) saldo_favor,
                   string_agg(distinct lower(replace(rp.forma_pago, '_', ' ')), ', ') formas
              from recibo_pago rp group by rp.recibo_id""";

    @Override
    public List<Reporte> reportes() {
        return List.of(
                reporte("cobros-por-fecha", "Cobros resumidos por fecha y forma de pago", CXC_CAJA,
                        "Recibos del día por forma de pago (el efectivo es neto de la devuelta), retenciones y total aplicado.",
                        "RptIngresosResumidoPorFecha", List.of(RANGO_FECHAS),
                        List.of(Columna.fecha("fecha", "Fecha"), Columna.entero("recibos", "Recibos"), Columna.moneda("efectivo", "Efectivo"),
                                Columna.moneda("tarjeta", "Tarjeta"), Columna.moneda("cheque", "Cheque"), Columna.moneda("transferencia", "Transferencia"),
                                Columna.moneda("saldo_favor", "Saldo a favor"), Columna.moneda("retenciones", "Retenciones"),
                                Columna.moneda("aplicado", "Total cobrado")),
                        """
                        select r.fecha, count(*) recibos, sum(coalesce(p.efectivo, 0) - r.devuelta) efectivo, sum(coalesce(p.tarjeta, 0)) tarjeta,
                               sum(coalesce(p.cheque, 0)) cheque, sum(coalesce(p.transferencia, 0)) transferencia,
                               sum(coalesce(p.saldo_favor, 0)) saldo_favor, sum(r.total_retenciones) retenciones, sum(r.total_aplicado) aplicado
                          from recibo r left join (%s) p on p.recibo_id = r.id
                         where r.compania_id = :cia and r.fecha between :desde and :hasta and r.estado = 'EMITIDO'
                         group by r.fecha order by r.fecha""".formatted(PAGOS_POR_RECIBO)),

                reporte("cobros-detalle", "Cobros detallados (recibos)", CXC_CAJA,
                        "Recibos del período con sus formas de pago; los anulados se muestran sin sumar.", "RptIngresosDetallados",
                        List.of(RANGO_FECHAS, CLIENTE),
                        List.of(Columna.texto("numero", "No."), Columna.fecha("fecha", "Fecha"), Columna.texto("cliente", "Cliente"),
                                Columna.texto("formas", "Formas de pago"), Columna.moneda("aplicado", "Aplicado"), Columna.moneda("retenciones", "Retenciones"),
                                Columna.moneda("recibido", "Recibido"), Columna.texto("estado", "Estado")),
                        """
                        select r.numero::text numero, r.fecha, c.nombre cliente, p.formas,
                               case when r.estado = 'EMITIDO' then r.total_aplicado else 0 end aplicado,
                               case when r.estado = 'EMITIDO' then r.total_retenciones else 0 end retenciones,
                               case when r.estado = 'EMITIDO' then r.total_recibido - r.devuelta else 0 end recibido, r.estado
                          from recibo r join cliente c on c.id = r.cliente_id left join (%s) p on p.recibo_id = r.id
                         where r.compania_id = :cia and r.fecha between :desde and :hasta and (:cliente is null or r.cliente_id = :cliente)
                         order by r.fecha, r.numero""".formatted(PAGOS_POR_RECIBO)),

                reporte("facturas-vencidas", "Facturas y notas de débito vencidas", CXC_CAJA,
                        "Documentos con balance pendiente y vencidos a la fecha de corte, con el teléfono del cliente.", "RptFacturasVencidas",
                        List.of(FECHA_CORTE, CLIENTE),
                        List.of(Columna.texto("cliente", "Cliente"), Columna.texto("telefono", "Teléfono"), Columna.texto("documento", "Documento"),
                                Columna.texto("comprobante", "e-NCF / NCF"), Columna.fecha("fecha", "Fecha"), Columna.fecha("vencimiento", "Vence"),
                                Columna.entero("dias", "Días vencido"), Columna.moneda("balance", "Pendiente")),
                        """
                        select * from (
                          select c.nombre cliente, coalesce(c.celular, c.telefono) telefono, 'Factura ' || f.numero documento,
                                 coalesce(f.encf, f.ncf_legacy) comprobante, f.fecha, f.fecha_vencimiento vencimiento,
                                 (:corte - f.fecha_vencimiento) dias, f.balance
                            from factura f join cliente c on c.id = f.cliente_id
                           where f.compania_id = :cia and f.estado = 'EMITIDA' and f.balance > 0 and f.fecha_vencimiento < :corte
                             and (:cliente is null or f.cliente_id = :cliente)
                          union all
                          select c.nombre, coalesce(c.celular, c.telefono), 'Nota de débito ' || n.numero, n.encf, n.fecha, n.fecha_vencimiento,
                                 (:corte - n.fecha_vencimiento), n.balance
                            from nota_debito n join cliente c on c.id = n.cliente_id
                           where n.compania_id = :cia and n.estado = 'EMITIDA' and n.balance > 0 and n.fecha_vencimiento < :corte
                             and (:cliente is null or n.cliente_id = :cliente)) x
                         order by cliente, vencimiento"""),

                reporte("egresos-caja", "Egresos de caja (gastos)", CXC_CAJA,
                        "Salidas de efectivo registradas en las cajas: beneficiario, concepto y comprobante.", "RptEgresoCaja",
                        List.of(RANGO_FECHAS),
                        List.of(Columna.fecha("fecha", "Fecha"), Columna.texto("numero", "No."), Columna.texto("caja", "Caja"),
                                Columna.texto("cajero", "Cajero"), Columna.texto("beneficiario", "Beneficiario"), Columna.texto("concepto", "Concepto"),
                                Columna.texto("ncf", "NCF"), Columna.moneda("itbis", "ITBIS"), Columna.moneda("monto", "Monto")),
                        """
                        select e.fecha, e.numero::text numero, '#' || s.numero caja, u.username cajero, e.beneficiario, e.concepto, e.ncf,
                               e.itbis, e.monto
                          from caja_egreso e join caja_sesion s on s.id = e.sesion_id join usuario u on u.id = s.usuario_id
                         where e.compania_id = :cia and e.fecha between :desde and :hasta
                         order by e.fecha, e.numero"""));
    }
}
