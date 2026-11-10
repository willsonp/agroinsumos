package com.agroinsumos.backend.ecf.xml;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.ecf.EcfProperties;
import com.agroinsumos.backend.ecf.TipoEcf;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valida el XML contra el XSD oficial de la DGII si está disponible en
 * classpath:dgii/xsd/e-CF-{tipo}.xsd (y dgii/xsd/RFCE-32.xsd). Los XSD se descargan del
 * portal de Facturación Electrónica de la DGII ("Documentación sobre e-CF").
 */
@Slf4j
@Component
public class EcfXsdValidator {

    private final EcfProperties props;
    private final Map<String, Schema> cache = new ConcurrentHashMap<>();

    public EcfXsdValidator(EcfProperties props) {
        this.props = props;
    }

    public void validarEcf(Document doc, TipoEcf tipo) {
        validar(doc, "dgii/xsd/e-CF-" + tipo.codigo() + ".xsd");
    }

    public void validarRfce(Document doc) {
        validar(doc, "dgii/xsd/RFCE-32.xsd");
    }

    private void validar(Document doc, String ruta) {
        var recurso = new ClassPathResource(ruta);
        if (!recurso.exists()) {
            if (props.exigirXsd()) {
                throw new BusinessException("XSD_NO_DISPONIBLE", "Falta el esquema oficial " + ruta);
            }
            log.debug("XSD {} no disponible: se omite validación de esquema", ruta);
            return;
        }
        try {
            Schema schema = cache.computeIfAbsent(ruta, r -> {
                try {
                    var sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                    sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    return sf.newSchema(recurso.getURL());
                } catch (Exception e) {
                    throw new IllegalStateException("XSD inválido " + r, e);
                }
            });
            schema.newValidator().validate(new DOMSource(doc));
        } catch (org.xml.sax.SAXException e) {
            throw new BusinessException("XML_NO_CUMPLE_XSD", "El e-CF no cumple el esquema DGII: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
