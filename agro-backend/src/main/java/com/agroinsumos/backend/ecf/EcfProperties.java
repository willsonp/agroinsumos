package com.agroinsumos.backend.ecf;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.ecf")
public record EcfProperties(
        @DefaultValue("TESTECF") Ambiente ambiente,
        Certificado certificado,
        @DefaultValue("false") boolean envioAutomatico,
        @DefaultValue("60s") Duration consultaEstadoIntervalo,
        @DefaultValue("10") int maxIntentosEnvio,
        /** Si es true, se rechaza emitir cuando no están los XSD oficiales en classpath:dgii/xsd. */
        @DefaultValue("false") boolean exigirXsd,
        /** Umbral para que una e-CF 32 se envíe completa en lugar de como Resumen (RFCE). */
        @DefaultValue("250000") BigDecimal umbralConsumoCompleto,
        @DefaultValue Urls urls) {

    public enum Ambiente {
        TESTECF("testecf"), CERTECF("certecf"), ECF("ecf");
        private final String ruta;
        Ambiente(String ruta) { this.ruta = ruta; }
        public String ruta() { return ruta; }
    }

    public record Certificado(String ruta, String clave) {}

    public record Urls(@DefaultValue("https://ecf.dgii.gov.do") String ecf,
                       @DefaultValue("https://fc.dgii.gov.do") String fc) {}

    public String baseEcf() { return urls.ecf() + "/" + ambiente.ruta(); }

    public String baseFc() { return urls.fc() + "/" + ambiente.ruta(); }
}
