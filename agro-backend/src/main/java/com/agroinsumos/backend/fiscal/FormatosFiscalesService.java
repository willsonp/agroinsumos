package com.agroinsumos.backend.fiscal;

import com.agroinsumos.backend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Genera los formatos de envío 606 (compras) y 607 (ventas) de la DGII.
 * Legacy: FrmFormato606 / FrmFormato607 (procs. ConsultaFormato606_Ambos, ConsultaFormato607_Ambos),
 * que solo producían un listado; aquí se genera además el TXT con el layout oficial.
 *
 * IMPORTANTE: validar el TXT con la "Herramienta de Pre-validación de Formatos de Envío" de la DGII
 * antes de remitirlo, y confirmar con el contador los criterios marcados como REVISAR.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormatosFiscalesService {

    private static final DateTimeFormatter AAAAMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final BigDecimal Z = BigDecimal.ZERO;

    private final JdbcTemplate jdbc;

    // =========================================================================================== 606
    public Formato606.Reporte formato606(Long cia, String periodo) {
        YearMonth ym = periodo(periodo);
        String rncEmpresa = rncEmpresa(cia);
        Date desde = Date.valueOf(ym.atDay(1)), hasta = Date.valueOf(ym.atEndOfMonth());
        List<String> adv = new ArrayList<>();
        List<Formato606.Linea> lineas = new ArrayList<>();

        // ---- Compras
        var compras = jdbc.queryForList("""
                select c.id, c.numero, c.fecha, coalesce(c.ncf_proveedor, c.encf_compra) ncf, c.condicion,
                       c.tipo_bien_servicio, c.tipo_retencion_isr, c.flete, c.total, c.total_itbis,
                       c.itbis_retenido, c.isr_retenido, p.rnc_cedula, p.nombre,
                       coalesce((select sum(l.importe) from compra_linea l join articulo a on a.id = l.articulo_id
                                  where l.compra_id = c.id and a.tipo = 'S'), 0) servicios,
                       coalesce((select sum(l.importe) from compra_linea l join articulo a on a.id = l.articulo_id
                                  where l.compra_id = c.id and a.tipo = 'B'), 0) bienes,
                       (select max(pp.fecha) from pago_proveedor_aplicacion pa join pago_proveedor pp on pp.id = pa.pago_id
                         where pa.compra_id = c.id and pp.estado = 'EMITIDO') fecha_pago,
                       (select string_agg(distinct f.forma_pago, ',') from pago_proveedor_aplicacion pa
                          join pago_proveedor pp on pp.id = pa.pago_id join pago_proveedor_forma f on f.pago_id = pp.id
                         where pa.compra_id = c.id and pp.estado = 'EMITIDO') formas
                  from compra c join proveedor p on p.id = c.proveedor_id
                 where c.compania_id = ? and c.fecha between ? and ? and c.estado <> 'ANULADA'
                 order by c.fecha, c.id
                """, cia, desde, hasta);
        for (var r : compras) {
            String ncf = (String) r.get("ncf");
            long numero = ((Number) r.get("numero")).longValue();
            if (ncf == null) {
                adv.add("Compra " + numero + " sin NCF ni e-CF 41: no se reporta (gasto no deducible)");
                continue;
            }
            String rnc = (String) r.get("rnc_cedula");
            if (rnc == null) adv.add("Compra " + numero + " (NCF " + ncf + "): el suplidor no tiene RNC/cédula");
            BigDecimal servicios = bd(r.get("servicios")).add(bd(r.get("flete")));
            BigDecimal bienes = bd(r.get("bienes"));
            BigDecimal itbis = bd(r.get("total_itbis"));
            BigDecimal facturado = bd(r.get("total")).subtract(itbis);
            // Ajuste de redondeo (e-CF 41 calcula ITBIS por tasa): la diferencia va a bienes o servicios
            BigDecimal dif = facturado.subtract(servicios).subtract(bienes);
            if (dif.signum() != 0) { if (bienes.signum() > 0) bienes = bienes.add(dif); else servicios = servicios.add(dif); }
            String formaPago = "CREDITO".equals(r.get("condicion")) ? "04" : formaPago606((String) r.get("formas"));
            BigDecimal retIsr = bd(r.get("isr_retenido"));
            lineas.add(new Formato606.Linea(rnc, tipoId(rnc), (String) r.get("tipo_bien_servicio"), ncf, null,
                    fecha(r.get("fecha")), fecha(r.get("fecha_pago")), servicios, bienes, facturado, itbis,
                    bd(r.get("itbis_retenido")), Z, Z, itbis, Z, retIsr.signum() > 0 ? (String) r.get("tipo_retencion_isr") : null,
                    retIsr, Z, Z, Z, Z, formaPago, "COMPRA", ((Number) r.get("id")).longValue(), (String) r.get("nombre")));
        }

        // ---- Egresos de caja con comprobante (incluye e-CF 43 de gastos menores)
        var egresos = jdbc.queryForList("""
                select e.id, e.numero, e.fecha, e.ncf, e.rnc, e.beneficiario, e.monto, e.itbis, e.tipo_bien_servicio,
                       e.ecf_documento_id
                  from caja_egreso e where e.compania_id = ? and e.fecha between ? and ? order by e.fecha, e.id
                """, cia, desde, hasta);
        for (var r : egresos) {
            String ncf = (String) r.get("ncf");
            long numero = ((Number) r.get("numero")).longValue();
            if (ncf == null) {
                adv.add("Egreso de caja " + numero + " sin comprobante: no se reporta (considere emitir e-CF 43)");
                continue;
            }
            boolean gastoMenor = r.get("ecf_documento_id") != null;
            // REVISAR: en gastos menores (B13/E43) se reporta el RNC del propio contribuyente
            String rnc = gastoMenor ? rncEmpresa : (String) r.get("rnc");
            if (rnc == null) adv.add("Egreso " + numero + " (NCF " + ncf + "): falta RNC del proveedor");
            BigDecimal itbis = bd(r.get("itbis"));
            BigDecimal facturado = bd(r.get("monto")).subtract(itbis);
            lineas.add(new Formato606.Linea(rnc, tipoId(rnc), (String) r.get("tipo_bien_servicio"), ncf, null,
                    fecha(r.get("fecha")), fecha(r.get("fecha")), facturado, Z, facturado, itbis, Z, Z, Z, itbis, Z,
                    null, Z, Z, Z, Z, Z, "01", "EGRESO_CAJA", ((Number) r.get("id")).longValue(), (String) r.get("beneficiario")));
        }

        var resumen = new Formato606.Resumen(lineas.size(),
                sum(lineas, Formato606.Linea::totalMontoFacturado), sum(lineas, Formato606.Linea::itbisFacturado),
                sum(lineas, Formato606.Linea::itbisRetenido), sum(lineas, Formato606.Linea::montoRetencionRenta),
                sum(lineas, Formato606.Linea::itbisPorAdelantar));
        return new Formato606.Reporte(rncEmpresa, periodo, lineas, resumen, adv);
    }

    public String txt606(Formato606.Reporte rep) {
        var sb = new StringBuilder();
        sb.append("606|").append(rep.rnc()).append('|').append(rep.periodo()).append('|').append(rep.lineas().size()).append("\r\n");
        for (var l : rep.lineas()) {
            sb.append(String.join("|", s(l.rncCedula()), s(l.tipoId()), s(l.tipoBienServicio()), s(l.ncf()),
                    s(l.ncfModificado()), f(l.fechaComprobante()), f(l.fechaPago()), m(l.montoServicios()),
                    m(l.montoBienes()), m(l.totalMontoFacturado()), m(l.itbisFacturado()), o(l.itbisRetenido()),
                    o(l.itbisProporcionalidad()), o(l.itbisLlevadoCosto()), o(l.itbisPorAdelantar()),
                    o(l.itbisPercibido()), s(l.tipoRetencionIsr()), o(l.montoRetencionRenta()), o(l.isrPercibido()),
                    o(l.impuestoSelectivo()), o(l.otrosImpuestos()), o(l.propinaLegal()), s(l.formaPago())))
              .append("\r\n");
        }
        return sb.toString();
    }

    // =========================================================================================== 607
    /**
     * @param incluirEcf REVISAR con el contador: como emisor electrónico la DGII ya recibe los e-CF;
     *                   este parámetro permite excluirlos si no deben reportarse en el 607.
     */
    public Formato607.Reporte formato607(Long cia, String periodo, boolean incluirEcf) {
        YearMonth ym = periodo(periodo);
        String rncEmpresa = rncEmpresa(cia);
        Date desde = Date.valueOf(ym.atDay(1)), hasta = Date.valueOf(ym.atEndOfMonth());
        List<String> adv = new ArrayList<>();
        List<Formato607.Linea> lineas = new ArrayList<>();

        // Formas de pago cobradas por factura, prorrateadas cuando un recibo abona varios documentos
        Map<Long, Map<String, BigDecimal>> cobros = new HashMap<>();
        jdbc.query("""
                select a.documento_id factura_id, p.forma_pago,
                       sum(case when p.forma_pago = 'EFECTIVO'
                                then (p.monto - r.devuelta * p.monto / nullif(ef.efectivo, 0)) else p.monto end
                           * a.monto / nullif(r.total_aplicado, 0)) monto
                  from recibo_aplicacion a
                  join recibo r on r.id = a.recibo_id and r.estado = 'EMITIDO'
                  join recibo_pago p on p.recibo_id = r.id
                  left join (select recibo_id, sum(monto) efectivo from recibo_pago where forma_pago = 'EFECTIVO' group by 1) ef
                         on ef.recibo_id = r.id
                  join factura f on f.id = a.documento_id
                 where a.documento_tipo = 'FACTURA' and f.compania_id = ? and f.fecha between ? and ?
                 group by 1, 2
                """, rs -> {
            cobros.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                    .put(rs.getString(2), rs.getBigDecimal(3) == null ? Z : rs.getBigDecimal(3));
        }, cia, desde, hasta);

        // ---- Facturas (e-CF y NCF legacy). Las facturas legacy anuladas van al 608, no aquí.
        var facturas = jdbc.queryForList("""
                select f.id, f.numero, f.fecha, coalesce(f.encf, f.ncf_legacy) ncf, f.cliente_rnc, f.cliente_nombre, f.condicion,
                       f.total, f.total_itbis,
                       coalesce(sum(a.retencion_itbis), 0) ret_itbis, coalesce(sum(a.retencion_isr), 0) ret_isr,
                       max(r.fecha) filter (where a.retencion_itbis + a.retencion_isr > 0) fecha_ret
                  from factura f
                  left join (recibo_aplicacion a join recibo r on r.id = a.recibo_id and r.estado = 'EMITIDO')
                         on a.documento_tipo = 'FACTURA' and a.documento_id = f.id
                 where f.compania_id = ? and f.fecha between ? and ?
                   and (f.encf is not null or (f.ncf_legacy is not null and f.estado <> 'ANULADA'))
                 group by f.id order by f.fecha, f.id
                """, cia, desde, hasta);
        for (var r : facturas) {
            String ncf = (String) r.get("ncf");
            if (!incluirEcf && ncf.startsWith("E")) continue;
            long id = ((Number) r.get("id")).longValue();
            BigDecimal total = bd(r.get("total")), itbis = bd(r.get("total_itbis"));
            BigDecimal retenciones = bd(r.get("ret_itbis")).add(bd(r.get("ret_isr")));
            BigDecimal efectivo = Z, chequeTransf = Z, tarjeta = Z, otras = Z, credito = Z;
            if ("CREDITO".equals(r.get("condicion"))) {
                // Venta a crédito: se informa como tal, sin desglosar los cobros posteriores.
                // Lo retenido por el cliente se informa en sus campos. REVISAR con el contador.
                credito = total.subtract(retenciones).max(Z);
            } else {
                // Contado: formas de pago de los recibos (prorrateadas si un recibo abona varios documentos).
                var c = cobros.getOrDefault(id, Map.of());
                chequeTransf = esc(c.get("CHEQUE")).add(esc(c.get("TRANSFERENCIA")));
                tarjeta = esc(c.get("TARJETA"));
                otras = esc(c.get("SALDO_A_FAVOR"));   // aplicación de notas de crédito
                // El resto (efectivo, o pago no identificado en facturas migradas) se presume efectivo
                efectivo = total.subtract(retenciones).subtract(chequeTransf).subtract(tarjeta).subtract(otras).max(Z);
            }
            String rnc = (String) r.get("cliente_rnc");
            if (rnc == null && total.compareTo(new BigDecimal("250000")) >= 0) {
                adv.add("Factura " + r.get("numero") + " (" + ncf + ") de RD$250,000 o más sin RNC/cédula del cliente");
            }
            if (retenciones.signum() > 0 && rnc == null) {
                adv.add("Factura " + r.get("numero") + " tiene retenciones pero el cliente no tiene RNC");
            }
            lineas.add(new Formato607.Linea(rnc, tipoId(rnc), ncf, null, "01", fecha(r.get("fecha")),
                    fecha(r.get("fecha_ret")), total.subtract(itbis), itbis, bd(r.get("ret_itbis")), Z,
                    bd(r.get("ret_isr")), Z, Z, Z, Z, efectivo, chequeTransf, tarjeta, credito, Z, Z, otras,
                    "FACTURA", id, (String) r.get("cliente_nombre")));
        }

        // ---- Notas de crédito (devoluciones, e-CF 34)
        // REVISAR: las formas de pago de una nota de crédito se dejan en cero.
        var ncs = jdbc.queryForList("""
                select d.id, d.fecha, d.encf, d.total, d.total_itbis, coalesce(f.encf, f.ncf_legacy) modificado,
                       f.cliente_rnc, f.cliente_nombre
                  from devolucion d join factura f on f.id = d.factura_id
                 where d.compania_id = ? and d.fecha between ? and ? and d.encf is not null order by d.fecha, d.id
                """, cia, desde, hasta);
        for (var r : ncs) {
            if (!incluirEcf) continue;
            String rnc = (String) r.get("cliente_rnc");
            BigDecimal total = bd(r.get("total")), itbis = bd(r.get("total_itbis"));
            lineas.add(new Formato607.Linea(rnc, tipoId(rnc), (String) r.get("encf"), (String) r.get("modificado"), "01",
                    fecha(r.get("fecha")), null, total.subtract(itbis), itbis, Z, Z, Z, Z, Z, Z, Z, Z, Z, Z, Z, Z, Z, Z,
                    "NOTA_CREDITO", ((Number) r.get("id")).longValue(), (String) r.get("cliente_nombre")));
        }

        // ---- Notas de débito (e-CF 33)
        var nds = jdbc.queryForList("""
                select n.id, n.fecha, n.encf, n.total, n.total_itbis, coalesce(f.encf, f.ncf_legacy) modificado,
                       f.cliente_rnc, f.cliente_nombre
                  from nota_debito n join factura f on f.id = n.factura_id
                 where n.compania_id = ? and n.fecha between ? and ? and n.encf is not null order by n.fecha, n.id
                """, cia, desde, hasta);
        for (var r : nds) {
            if (!incluirEcf) continue;
            String rnc = (String) r.get("cliente_rnc");
            BigDecimal total = bd(r.get("total")), itbis = bd(r.get("total_itbis"));
            lineas.add(new Formato607.Linea(rnc, tipoId(rnc), (String) r.get("encf"), (String) r.get("modificado"), "01",
                    fecha(r.get("fecha")), null, total.subtract(itbis), itbis, Z, Z, Z, Z, Z, Z, Z, Z, Z, Z, total, Z, Z, Z,
                    "NOTA_DEBITO", ((Number) r.get("id")).longValue(), (String) r.get("cliente_nombre")));
        }

        BigDecimal notasCredito = lineas.stream().filter(l -> l.origen().equals("NOTA_CREDITO"))
                .map(l -> l.montoFacturado().add(l.itbisFacturado())).reduce(Z, BigDecimal::add);
        var resumen = new Formato607.Resumen(lineas.size(), sum(lineas, Formato607.Linea::montoFacturado),
                sum(lineas, Formato607.Linea::itbisFacturado), sum(lineas, Formato607.Linea::itbisRetenidoTerceros),
                sum(lineas, Formato607.Linea::retencionRentaTerceros), notasCredito);
        return new Formato607.Reporte(rncEmpresa, periodo, lineas, resumen, adv);
    }

    public String txt607(Formato607.Reporte rep) {
        var sb = new StringBuilder();
        sb.append("607|").append(rep.rnc()).append('|').append(rep.periodo()).append('|').append(rep.lineas().size()).append("\r\n");
        for (var l : rep.lineas()) {
            sb.append(String.join("|", s(l.rncCedula()), s(l.tipoId()), s(l.ncf()), s(l.ncfModificado()),
                    s(l.tipoIngreso()), f(l.fechaComprobante()), f(l.fechaRetencion()), m(l.montoFacturado()),
                    m(l.itbisFacturado()), o(l.itbisRetenidoTerceros()), o(l.itbisPercibido()),
                    o(l.retencionRentaTerceros()), o(l.isrPercibido()), o(l.impuestoSelectivo()), o(l.otrosImpuestos()),
                    o(l.propinaLegal()), o(l.efectivo()), o(l.chequeTransferencia()), o(l.tarjeta()), o(l.ventaCredito()),
                    o(l.bonos()), o(l.permuta()), o(l.otrasFormas())))
              .append("\r\n");
        }
        return sb.toString();
    }

    public static String nombreArchivo(String formato, String rnc, String periodo) {
        return "DGII_F_" + formato + "_" + rnc + "_" + periodo + ".TXT";
    }

    // =========================================================================================== utilidades
    private static YearMonth periodo(String periodo) {
        try {
            if (periodo == null || !periodo.matches("\\d{6}")) throw new DateTimeParseException("", "", 0);
            return YearMonth.parse(periodo, DateTimeFormatter.ofPattern("yyyyMM"));
        } catch (DateTimeParseException e) {
            throw new BusinessException("PERIODO_INVALIDO", "Periodo inválido (formato AAAAMM): " + periodo);
        }
    }

    private String rncEmpresa(Long cia) {
        return jdbc.queryForObject("select rnc from compania where id = ?", String.class, cia);
    }

    /** 1 = RNC (9 dígitos), 2 = Cédula (11 dígitos). */
    static String tipoId(String rnc) {
        if (rnc == null) return null;
        return rnc.length() == 9 ? "1" : rnc.length() == 11 ? "2" : null;
    }

    /** 01 Efectivo, 02 Cheque/Transferencia/Depósito, 03 Tarjeta, 04 Crédito, 07 Mixto. */
    static String formaPago606(String formas) {
        if (formas == null) return "04";
        Set<String> f = Arrays.stream(formas.split(",")).map(x -> switch (x) {
            case "EFECTIVO" -> "01";
            case "CHEQUE", "TRANSFERENCIA" -> "02";
            case "TARJETA" -> "03";
            default -> "07";
        }).collect(Collectors.toSet());
        return f.size() == 1 ? f.iterator().next() : "07";
    }

    private static BigDecimal bd(Object o) { return o == null ? Z : ((BigDecimal) o).setScale(2, RoundingMode.HALF_UP); }

    private static BigDecimal esc(BigDecimal b) { return b == null ? Z : b.setScale(2, RoundingMode.HALF_UP); }

    private static LocalDate fecha(Object o) { return o == null ? null : ((Date) o).toLocalDate(); }

    private static String s(String v) { return v == null ? "" : v; }

    private static String f(LocalDate d) { return d == null ? "" : d.format(AAAAMMDD); }

    /** Monto obligatorio: siempre con 2 decimales. */
    private static String m(BigDecimal b) { return (b == null ? Z : b).setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    /** Monto opcional: vacío si es cero. */
    private static String o(BigDecimal b) { return b == null || b.signum() == 0 ? "" : m(b); }

    private static <T> BigDecimal sum(List<T> l, java.util.function.Function<T, BigDecimal> f) {
        return l.stream().map(f).filter(Objects::nonNull).reduce(Z, BigDecimal::add);
    }
}
