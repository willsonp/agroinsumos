package com.agroinsumos.backend.ecf.xml;

import com.agroinsumos.backend.compania.Compania;
import com.agroinsumos.backend.ecf.TipoEcf;
import com.agroinsumos.backend.ecf.dto.EcfDtos.EmitirEcfRequest;
import com.agroinsumos.backend.ecf.emision.EcfCalculadora.Linea;
import com.agroinsumos.backend.ecf.emision.EcfCalculadora.Totales;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.agroinsumos.backend.ecf.xml.XmlUtil.campo;
import static com.agroinsumos.backend.ecf.xml.XmlUtil.hijo;

/**
 * Genera el XML del e-CF según el "Formato Comprobante Fiscal Electrónico (e-CF) v1.0" de la DGII.
 * El ORDEN de los elementos es obligatorio (xs:sequence en los XSD). Antes de producción, validar
 * contra los XSD oficiales colocándolos en src/main/resources/dgii/xsd (ver EcfXsdValidator).
 */
@Component
public class EcfXmlBuilder {

    public record Datos(TipoEcf tipo, String encf, LocalDate fechaVencimientoSecuencia, LocalDate fechaEmision,
                        Compania emisor, EmitirEcfRequest solicitud, Totales totales, LocalDateTime fechaHoraFirma) {}

    public Document construir(Datos d) {
        Document doc = XmlUtil.nuevo();
        Element ecf = doc.createElement("ECF");
        doc.appendChild(ecf);

        Element enc = hijo(ecf, "Encabezado");
        campo(enc, "Version", "1.0");
        idDoc(hijo(enc, "IdDoc"), d);
        emisor(hijo(enc, "Emisor"), d);
        comprador(enc, d);
        totales(hijo(enc, "Totales"), d.totales());

        Element detalles = hijo(ecf, "DetallesItems");
        for (Linea l : d.totales().lineas()) {
            item(hijo(detalles, "Item"), l);
        }

        if (d.tipo().esNota() && d.solicitud().referencia() != null) {
            var r = d.solicitud().referencia();
            Element ref = hijo(ecf, "InformacionReferencia");
            campo(ref, "NCFModificado", r.ncfModificado());
            campo(ref, "FechaNCFModificado", r.fechaNcfModificado());
            campo(ref, "CodigoModificacion", r.codigoModificacion());
            campo(ref, "RazonModificacion", r.razonModificacion());
        }
        campo(ecf, "FechaHoraFirma", d.fechaHoraFirma());
        return doc;
    }

    private static void idDoc(Element e, Datos d) {
        var s = d.solicitud();
        campo(e, "TipoeCF", d.tipo().codigo());
        campo(e, "eNCF", d.encf());
        if (d.tipo() != TipoEcf.FACTURA_CONSUMO && d.tipo() != TipoEcf.NOTA_CREDITO) {
            campo(e, "FechaVencimientoSecuencia", d.fechaVencimientoSecuencia());
        }
        if (d.tipo() == TipoEcf.NOTA_CREDITO) {
            // 0: dentro de 30 días desde el comprobante modificado (afecta ITBIS); 1: más de 30 días.
            boolean masDe30 = s.referencia() != null
                    && s.referencia().fechaNcfModificado().plusDays(30).isBefore(d.fechaEmision());
            campo(e, "IndicadorNotaCredito", masDe30 ? 1 : 0);
        }
        if (d.tipo() != TipoEcf.GASTOS_MENORES) campo(e, "IndicadorMontoGravado", 0); // precios sin ITBIS incluido
        if (!d.tipo().esDeAdquisicion()) campo(e, "TipoIngresos", s.tipoIngresos());
        campo(e, "TipoPago", s.tipoPago());
        if (s.tipoPago() == 2) campo(e, "FechaLimitePago", s.fechaLimitePago());
    }

    private static void emisor(Element e, Datos d) {
        Compania c = d.emisor();
        campo(e, "RNCEmisor", c.getRnc());
        campo(e, "RazonSocialEmisor", c.getNombre());
        campo(e, "NombreComercial", c.getNombreComercial());
        campo(e, "DireccionEmisor", c.getDireccion());
        campo(e, "CorreoEmisor", c.getCorreo());
        campo(e, "WebSite", c.getSitioWeb());
        campo(e, "FechaEmision", d.fechaEmision());
    }

    private static void comprador(Element enc, Datos d) {
        var c = d.solicitud().comprador();
        if (c == null || (c.rnc() == null && c.razonSocial() == null)) return;
        Element e = hijo(enc, "Comprador");
        campo(e, "RNCComprador", c.rnc());
        campo(e, "RazonSocialComprador", c.razonSocial());
        campo(e, "CorreoComprador", c.correo());
        campo(e, "DireccionComprador", c.direccion());
    }

    private static void totales(Element e, Totales t) {
        if (t.montoGravadoTotal().signum() > 0) {
            campo(e, "MontoGravadoTotal", t.montoGravadoTotal());
            if (t.tieneI1()) campo(e, "MontoGravadoI1", t.montoGravadoI1());
            if (t.tieneI2()) campo(e, "MontoGravadoI2", t.montoGravadoI2());
            if (t.tieneI3()) campo(e, "MontoGravadoI3", t.montoGravadoI3());
        }
        if (t.tieneExento()) campo(e, "MontoExento", t.montoExento());
        if (t.tieneI1()) campo(e, "ITBIS1", entero(BigDecimal.valueOf(18)));
        if (t.tieneI2()) campo(e, "ITBIS2", entero(BigDecimal.valueOf(16)));
        if (t.tieneI3()) campo(e, "ITBIS3", "0");
        if (t.montoGravadoTotal().signum() > 0) {
            campo(e, "TotalITBIS", t.totalItbis());
            if (t.tieneI1()) campo(e, "TotalITBIS1", t.totalItbis1());
            if (t.tieneI2()) campo(e, "TotalITBIS2", t.totalItbis2());
            if (t.tieneI3()) campo(e, "TotalITBIS3", t.totalItbis3());
        }
        campo(e, "MontoTotal", t.montoTotal());
        // e-CF 41: retenciones practicadas al vendedor (van después de MontoTotal en el XSD)
        if (t.tieneRetenciones()) {
            campo(e, "TotalITBISRetenido", t.totalItbisRetenido());
            campo(e, "TotalISRRetencion", t.totalIsrRetenido());
        }
    }

    private static void item(Element e, Linea l) {
        var it = l.item();
        campo(e, "NumeroLinea", l.numero());
        campo(e, "IndicadorFacturacion", it.indicadorFacturacion());
        if (it.tieneRetencion()) {
            Element ret = hijo(e, "Retencion");
            campo(ret, "IndicadorAgenteRetencionoPercepcion", 1); // 1 = retención
            campo(ret, "MontoITBISRetenido", it.itbisRetenido() == null ? BigDecimal.ZERO : it.itbisRetenido());
            campo(ret, "MontoISRRetenido", it.isrRetenido() == null ? BigDecimal.ZERO : it.isrRetenido());
        }
        campo(e, "NombreItem", it.nombre());
        campo(e, "IndicadorBienoServicio", it.bienOServicio());
        campo(e, "CantidadItem", it.cantidad().stripTrailingZeros().toPlainString());
        campo(e, "PrecioUnitarioItem", it.precioUnitario().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
        if (l.descuento().signum() > 0) campo(e, "DescuentoMonto", l.descuento());
        campo(e, "MontoItem", l.montoItem());
    }

    private static String entero(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }
}
