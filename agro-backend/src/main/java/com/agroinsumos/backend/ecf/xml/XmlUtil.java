package com.agroinsumos.backend.ecf.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class XmlUtil {
    public static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    public static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private XmlUtil() {}

    public static Document nuevo() {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parser endurecido contra XXE (el XML de la semilla viene de la red). */
    public static Document parsear(byte[] xml) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML inválido: " + e.getMessage(), e);
        }
    }

    /** Serializa sin sangría ni espacios extra (cualquier cambio invalida la firma). */
    public static String serializar(Document doc) {
        try {
            var tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            t.setOutputProperty(OutputKeys.INDENT, "no");
            t.setOutputProperty(OutputKeys.STANDALONE, "no");
            var w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Element hijo(Element padre, String nombre) {
        Element e = padre.getOwnerDocument().createElement(nombre);
        padre.appendChild(e);
        return e;
    }

    /** Agrega el elemento solo si el valor no es nulo/vacío (los campos opcionales vacíos no se envían). */
    public static void campo(Element padre, String nombre, Object valor) {
        if (valor == null) return;
        String texto = switch (valor) {
            case BigDecimal b -> monto(b);
            case LocalDateTime dt -> dt.format(FECHA_HORA);
            case LocalDate d -> d.format(FECHA);
            default -> valor.toString().trim();
        };
        if (texto.isEmpty()) return;
        hijo(padre, nombre).setTextContent(texto);
    }

    public static String monto(BigDecimal b) {
        return b.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
