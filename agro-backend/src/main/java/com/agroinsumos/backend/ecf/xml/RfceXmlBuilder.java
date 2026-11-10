package com.agroinsumos.backend.ecf.xml;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static com.agroinsumos.backend.ecf.xml.XmlUtil.campo;
import static com.agroinsumos.backend.ecf.xml.XmlUtil.hijo;

/**
 * Resumen de Factura de Consumo Electrónica (RFCE): para e-CF 32 con monto total menor a
 * RD$250,000 solo se envía este resumen a la DGII; el e-CF completo se conserva firmado.
 * Se construye a partir del e-CF ya firmado (reutiliza sus nodos) para garantizar coherencia.
 */
@Component
public class RfceXmlBuilder {

    public Document construir(Document ecfFirmado, String codigoSeguridad) {
        Element encOrigen = (Element) ecfFirmado.getElementsByTagName("Encabezado").item(0);
        Document doc = XmlUtil.nuevo();
        Element rfce = doc.createElement("RFCE");
        doc.appendChild(rfce);
        Element enc = hijo(rfce, "Encabezado");
        campo(enc, "Version", "1.0");

        Element idDoc = hijo(enc, "IdDoc");
        copiar(encOrigen, "IdDoc", idDoc, "TipoeCF", "eNCF", "TipoIngresos", "TipoPago");
        Element emisor = hijo(enc, "Emisor");
        copiar(encOrigen, "Emisor", emisor, "RNCEmisor", "RazonSocialEmisor", "FechaEmision");
        if (encOrigen.getElementsByTagName("Comprador").getLength() > 0) {
            Element comp = hijo(enc, "Comprador");
            copiar(encOrigen, "Comprador", comp, "RNCComprador", "RazonSocialComprador");
        }
        Element tot = hijo(enc, "Totales");
        copiar(encOrigen, "Totales", tot, "MontoGravadoTotal", "MontoGravadoI1", "MontoGravadoI2", "MontoGravadoI3",
                "MontoExento", "TotalITBIS", "TotalITBIS1", "TotalITBIS2", "TotalITBIS3", "MontoTotal");
        campo(enc, "CodigoSeguridadeCF", codigoSeguridad);
        return doc;
    }

    private static void copiar(Element enc, String seccion, Element destino, String... campos) {
        Element origen = (Element) enc.getElementsByTagName(seccion).item(0);
        for (String c : campos) {
            var nodos = origen.getElementsByTagName(c);
            if (nodos.getLength() > 0) campo(destino, c, nodos.item(0).getTextContent());
        }
    }
}
