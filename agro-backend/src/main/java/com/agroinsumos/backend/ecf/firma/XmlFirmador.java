package com.agroinsumos.backend.ecf.firma;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import java.util.Base64;
import java.util.List;

/**
 * Firma XMLDSig "enveloped" exigida por la DGII: RSA-SHA256, digest SHA-256,
 * canonicalización C14N inclusiva, Reference URI="" y certificado X509 en KeyInfo.
 * La firma se agrega como último hijo del elemento raíz (ECF, RFCE, SemillaModel...).
 */
@Component
public class XmlFirmador {

    private static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    public record Resultado(Document documento, String signatureValue) {
        /** Código de seguridad del e-CF: primeros 6 caracteres del SignatureValue. */
        public String codigoSeguridad() { return signatureValue.substring(0, 6); }
    }

    public Resultado firmar(Document doc, CertificadoProvider.Credencial cred) {
        try {
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
            Reference ref = fac.newReference("",
                    fac.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                    null, null);
            SignedInfo si = fac.newSignedInfo(
                    fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                    fac.newSignatureMethod(RSA_SHA256, null),
                    List.of(ref));
            KeyInfoFactory kif = fac.getKeyInfoFactory();
            var keyInfo = kif.newKeyInfo(List.of(kif.newX509Data(List.of(cred.certificado()))));
            Element raiz = doc.getDocumentElement();
            XMLSignature signature = fac.newXMLSignature(si, keyInfo);
            signature.sign(new DOMSignContext(cred.clavePrivada(), raiz));
            String valor = Base64.getEncoder().encodeToString(signature.getSignatureValue().getValue());
            return new Resultado(doc, valor);
        } catch (Exception e) {
            throw new IllegalStateException("Error firmando XML: " + e.getMessage(), e);
        }
    }
}
