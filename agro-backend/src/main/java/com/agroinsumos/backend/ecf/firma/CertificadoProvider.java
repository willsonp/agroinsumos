package com.agroinsumos.backend.ecf.firma;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** Fuente del certificado digital de la compañía (P12 en disco, HSM, Vault...). */
public interface CertificadoProvider {

    record Credencial(PrivateKey clavePrivada, X509Certificate certificado) {}

    Credencial obtener(Long companiaId);
}
