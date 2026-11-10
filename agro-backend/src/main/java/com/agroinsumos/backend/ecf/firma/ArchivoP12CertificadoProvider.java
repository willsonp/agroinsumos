package com.agroinsumos.backend.ecf.firma;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.ecf.EcfProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;

/**
 * Carga el certificado de firma (persona física autorizada ante la DGII) desde un archivo .p12.
 * Implementación mono-compañía; para varias compañías, crear otra implementación que lea
 * ruta/clave por compania_id desde un almacén seguro.
 */
@Slf4j
@Component
public class ArchivoP12CertificadoProvider implements CertificadoProvider {

    private final EcfProperties props;
    private volatile Credencial cache;

    public ArchivoP12CertificadoProvider(EcfProperties props) {
        this.props = props;
    }

    @Override
    public Credencial obtener(Long companiaId) {
        Credencial c = cache;
        if (c == null) {
            synchronized (this) {
                if (cache == null) cache = cargar();
                c = cache;
            }
        }
        try {
            c.certificado().checkValidity(new Date());
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CERTIFICADO_VENCIDO",
                    "El certificado digital de firma está vencido o aún no es válido");
        }
        return c;
    }

    private Credencial cargar() {
        var cfg = props.certificado();
        if (cfg == null || cfg.ruta() == null || cfg.ruta().isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CERTIFICADO_NO_CONFIGURADO",
                    "Configure ECF_CERT_PATH y ECF_CERT_PASSWORD con el certificado digital .p12");
        }
        char[] clave = cfg.clave() == null ? new char[0] : cfg.clave().toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(cfg.ruta()))) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, clave);
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias)) {
                    var cert = (X509Certificate) ks.getCertificate(alias);
                    log.info("Certificado e-CF cargado: {} (vence {})", cert.getSubjectX500Principal(), cert.getNotAfter());
                    return new Credencial((PrivateKey) ks.getKey(alias, clave), cert);
                }
            }
            throw new IllegalStateException("El .p12 no contiene una clave privada");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CERTIFICADO_INVALIDO",
                    "No se pudo cargar el certificado digital: " + e.getMessage());
        }
    }
}
