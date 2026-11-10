package com.agroinsumos.backend.ecf;

import com.agroinsumos.backend.IntegrationTestBase;
import com.agroinsumos.backend.ecf.dgii.DgiiClient;
import com.agroinsumos.backend.ecf.secuencia.EcfSecuenciaService;
import com.agroinsumos.backend.ecf.xml.XmlUtil;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EcfIntegrationTest extends IntegrationTestBase {

    @MockitoBean DgiiClient dgii;
    @Autowired EcfSecuenciaService secuencias;
    @Autowired TransactionTemplate tx;

    String admin;

    static final String FACTURA_31 = """
            {"tipoEcf":31,"tipoIngresos":"01","tipoPago":1,
             "comprador":{"rnc":"101010101","razonSocial":"FINCA EL PROGRESO SRL"},
             "items":[
               {"nombre":"Fertilizante 15-15-15 (saco 100 lb)","bienOServicio":1,"cantidad":3,"precioUnitario":1850.00,"indicadorFacturacion":1},
               {"nombre":"Semilla de arroz certificada","bienOServicio":1,"cantidad":2.5,"precioUnitario":400,"descuento":50,"indicadorFacturacion":4}
             ],"origenTipo":"FACTURA","origenId":51205}""";

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        for (int tipo : new int[]{31, 32, 34}) {
            mvc.perform(post("/api/v1/ecf/secuencias").header("Authorization", "Bearer " + admin)
                            .contentType("application/json")
                            .content("{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":1000,\"fechaVencimiento\":\"2030-12-31\"}".formatted(tipo)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void emiteFacturaCreditoFiscalFirmadaYCalculaTotales() throws Exception {
        var json = mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(FACTURA_31))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.encf").value("E310000000001"))
                .andExpect(jsonPath("$.estado").value("FIRMADO"))
                .andExpect(jsonPath("$.montoGravado").value(5550.00))
                .andExpect(jsonPath("$.montoExento").value(950.00))
                .andExpect(jsonPath("$.totalItbis").value(999.00))
                .andExpect(jsonPath("$.montoTotal").value(7499.00))
                .andExpect(jsonPath("$.resumenConsumo").value(false))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(json, "$.id");
        String codigo = JsonPath.read(json, "$.codigoSeguridad");
        assertThat(codigo).hasSize(6);

        String xml = mvc.perform(get("/api/v1/ecf/documentos/" + id + "/xml").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var doc = XmlUtil.parsear(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(texto(doc.getDocumentElement(), "RNCEmisor")).isEqualTo("131000001");
        assertThat(texto(doc.getDocumentElement(), "FechaVencimientoSecuencia")).isEqualTo("31-12-2030");
        assertThat(texto(doc.getDocumentElement(), "MontoItem")).isEqualTo("5550.00");
        assertThat(texto(doc.getDocumentElement(), "SignatureValue")).startsWith(codigo);
        assertThat(firmaValida(doc)).as("la firma XMLDSig debe validar con el certificado").isTrue();

        mvc.perform(get("/api/v1/ecf/documentos/" + id + "/timbre").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("https://ecf.dgii.gov.do/testecf/consultatimbre?RncEmisor=131000001"),
                        org.hamcrest.Matchers.containsString("ENCF=E310000000001"),
                        org.hamcrest.Matchers.containsString("MontoTotal=7499.00"))));
    }

    @Test
    void facturaCreditoFiscalSinRncEsRechazadaYNoConsumeSecuencia() throws Exception {
        mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content(FACTURA_31.replace("\"comprador\":{\"rnc\":\"101010101\",\"razonSocial\":\"FINCA EL PROGRESO SRL\"},", "")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RNC_COMPRADOR_REQUERIDO"));
        mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(FACTURA_31))
                .andExpect(jsonPath("$.encf").value("E310000000001"));
    }

    @Test
    void consumoMenorA250MilGeneraRfceYSeEnviaAlEndpointDeResumen() throws Exception {
        when(dgii.enviarRfce(anyLong(), anyString(), anyString()))
                .thenReturn(new DgiiClient.RfceResultado(1, "Aceptado", List.of(), "E320000000001", true));
        var json = mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("""
                                {"tipoEcf":32,"tipoIngresos":"01","tipoPago":1,
                                 "items":[{"nombre":"Herbicida 1L","bienOServicio":1,"cantidad":1,"precioUnitario":500,"indicadorFacturacion":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resumenConsumo").value(true))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(json, "$.id");

        String rfce = mvc.perform(get("/api/v1/ecf/documentos/" + id + "/xml?rfce=true").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var doc = XmlUtil.parsear(rfce.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("RFCE");
        assertThat(texto(doc.getDocumentElement(), "CodigoSeguridadeCF")).isEqualTo(JsonPath.read(json, "$.codigoSeguridad"));
        assertThat(texto(doc.getDocumentElement(), "MontoTotal")).isEqualTo("590.00");
        assertThat(firmaValida(doc)).isTrue();

        mvc.perform(post("/api/v1/ecf/documentos/" + id + "/enviar").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ACEPTADO"));
        verify(dgii).enviarRfce(anyLong(), eq("131000001E320000000001.xml"), anyString());
        verify(dgii, never()).enviarEcf(anyLong(), anyString(), anyString());
    }

    @Test
    void envioYConsultaDeEstadoPorTrackId() throws Exception {
        when(dgii.enviarEcf(anyLong(), anyString(), anyString()))
                .thenReturn(new DgiiClient.RecepcionResultado("track-123", null, null));
        when(dgii.consultarEstado(anyLong(), eq("track-123"))).thenReturn(new DgiiClient.EstadoResultado(
                "track-123", 4, "Aceptado Condicional", "131000001", "E310000000001", true, null,
                List.of(new DgiiClient.Mensaje("Advertencia de prueba", 100))));

        Integer id = JsonPath.read(mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content(FACTURA_31)).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/ecf/documentos/" + id + "/enviar").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.estado").value("ENVIADO")).andExpect(jsonPath("$.trackId").value("track-123"));
        // No se puede reenviar
        mvc.perform(post("/api/v1/ecf/documentos/" + id + "/enviar").header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ECF_YA_ENVIADO"));

        mvc.perform(post("/api/v1/ecf/documentos/" + id + "/consultar-estado").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.estado").value("ACEPTADO_CONDICIONAL"))
                .andExpect(jsonPath("$.mensajesDgii").value(org.hamcrest.Matchers.containsString("Advertencia de prueba")));
    }

    @Test
    void dgiiCaidaDejaDocumentoEnErrorParaReintento() throws Exception {
        when(dgii.enviarEcf(anyLong(), anyString(), anyString())).thenThrow(new RuntimeException("Connection refused"));
        Integer id = JsonPath.read(mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content(FACTURA_31)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/ecf/documentos/" + id + "/enviar").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.estado").value("ERROR_ENVIO"))
                .andExpect(jsonPath("$.intentosEnvio").value(1))
                .andExpect(jsonPath("$.ultimoError").value("Connection refused"));
    }

    @Test
    void notaCreditoExigeReferencia() throws Exception {
        mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(FACTURA_31.replace("\"tipoEcf\":31", "\"tipoEcf\":34")))
                .andExpect(jsonPath("$.code").value("REFERENCIA_REQUERIDA"));
        mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(FACTURA_31.replace("\"tipoEcf\":31", "\"tipoEcf\":34")
                                .replace("\"origenTipo\"", "\"referencia\":{\"ncfModificado\":\"E310000000001\",\"fechaNcfModificado\":\"2026-11-01\",\"codigoModificacion\":3},\"origenTipo\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.encf").value("E340000000001"));
    }

    @Test
    void reservaConcurrenteNoDuplicaENcf() throws Exception {
        int hilos = 16;
        var pool = Executors.newFixedThreadPool(hilos);
        var inicio = new CountDownLatch(1);
        List<Future<String>> futuros = new ArrayList<>();
        for (int i = 0; i < hilos * 4; i++) {
            futuros.add(pool.submit(() -> {
                inicio.await();
                return tx.execute(s -> secuencias.reservar(companiaId, TipoEcf.FACTURA_CREDITO_FISCAL).encf());
            }));
        }
        inicio.countDown();
        Set<String> encfs = new HashSet<>();
        for (var f : futuros) encfs.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(encfs).hasSize(hilos * 4).contains("E310000000001", "E310000000064");
    }

    @Test
    void tipoNoSoportadoYSecuenciaAgotada() throws Exception {
        mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(FACTURA_31.replace("\"tipoEcf\":31", "\"tipoEcf\":46")))
                .andExpect(jsonPath("$.code").value("TIPO_ECF_NO_SOPORTADO"));
        jdbc.update("update ecf_secuencia set ultima_usada = secuencia_hasta where tipo_ecf = 31");
        mvc.perform(post("/api/v1/ecf/documentos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(FACTURA_31))
                .andExpect(jsonPath("$.code").value("SECUENCIA_AGOTADA"));
    }

    private static String texto(Element raiz, String tag) {
        var n = raiz.getElementsByTagNameNS("*", tag);
        if (n.getLength() == 0) n = raiz.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent();
    }

    private static boolean firmaValida(org.w3c.dom.Document doc) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream("src/test/resources/ecf/test-cert.p12")) {
            ks.load(in, "test1234".toCharArray());
        }
        var cert = (X509Certificate) ks.getCertificate("test");
        var nodo = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        var ctx = new DOMValidateContext(cert.getPublicKey(), nodo);
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
    }
}
