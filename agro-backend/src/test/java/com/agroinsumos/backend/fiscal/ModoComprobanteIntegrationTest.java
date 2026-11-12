package com.agroinsumos.backend.fiscal;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Modo de comprobante de la empresa: e-CF (firma), NCF tradicional serie B (B01/B02/B04 de rangos autorizados) o
 * sin comprobante fiscal (solo número interno). Facturas, notas de crédito y el 607 respetan el modo.
 */
class ModoComprobanteIntegrationTest extends IntegrationTestBase {

    String admin;
    long urea, consumidor, finca;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Urea","tipo":"S","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,"precioContado":2000}"""
                .formatted(depto, unidad), 201));
        consumidor = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Juan Pérez","tipoEcfPreferido":32,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":0}""", 201));
        finca = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":0}""", 201));
    }

    @Test
    void ncfSerieBYSinComprobante() throws Exception {
        // ---- NCF serie B
        modo("NCF");
        assertThat(code(post("/api/v1/ventas/facturas", factura(finca), 422))).isEqualTo("SECUENCIA_NO_CONFIGURADA");
        String vence = LocalDate.now().plusYears(1).toString();
        String sec = post("/api/v1/ecf/secuencias", """
                {"tipoEcf":31,"serie":"B","secuenciaDesde":101,"secuenciaHasta":500,"fechaVencimiento":"%s"}""".formatted(vence), 201);
        assertThat(JsonPath.<String>read(sec, "$.prefijo")).isEqualTo("B01");
        assertThat(code(post("/api/v1/ecf/secuencias", """
                {"tipoEcf":32,"serie":"B","secuenciaDesde":1,"secuenciaHasta":100000000,"fechaVencimiento":"%s"}""".formatted(vence), 422)))
                .isEqualTo("RANGO_INVALIDO");   // los NCF B tienen 8 dígitos
        for (int t : new int[]{32, 34}) {
            post("/api/v1/ecf/secuencias", """
                    {"tipoEcf":%d,"serie":"B","secuenciaDesde":1,"secuenciaHasta":500,"fechaVencimiento":"%s"}""".formatted(t, vence), 201);
        }
        String f1 = post("/api/v1/ventas/facturas", factura(finca), 201);
        assertThat(JsonPath.<String>read(f1, "$.encf")).isEqualTo("B0100000101");
        assertThat(JsonPath.<Object>read(f1, "$.ecfDocumentoId")).isNull();          // no hay e-CF firmado
        assertThat(JsonPath.<Number>read(f1, "$.total").doubleValue()).isEqualTo(2360.0);
        String f2 = post("/api/v1/ventas/facturas", factura(consumidor), 201);
        assertThat(JsonPath.<String>read(f2, "$.encf")).isEqualTo("B0200000001");
        // Las reglas fiscales siguen: B01 exige RNC del comprador
        assertThat(code(post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","tipoEcf":31,"lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(consumidor, urea), 422)))
                .isEqualTo("RNC_COMPRADOR_REQUERIDO");
        // Anular: nota de crédito B04 que modifica el B01
        String nc = post("/api/v1/ventas/facturas/" + id(f1) + "/anular", "{\"motivo\":\"Error de precio\"}", 200);
        assertThat(JsonPath.<String>read(nc, "$.encf")).isEqualTo("B0400000001");

        // ---- Sin comprobante fiscal
        modo("NINGUNO");
        String f3 = post("/api/v1/ventas/facturas", factura(finca), 201);
        assertThat(JsonPath.<Object>read(f3, "$.encf")).isNull();
        assertThat(JsonPath.<Number>read(f3, "$.total").doubleValue()).isEqualTo(2360.0);
        assertThat(JsonPath.<Number>read(f3, "$.numero").longValue()).isEqualTo(3);

        // 607: entran las facturas con NCF (B01 y B02) y la nota B04; la de sin comprobante no
        String periodo = LocalDate.now().toString().substring(0, 7).replace("-", "");
        String rep = getJson("/api/v1/fiscal/607?periodo=" + periodo);
        List<String> ncfs = JsonPath.read(rep, "$.lineas[*].ncf");
        assertThat(ncfs).contains("B0100000101", "B0200000001", "B0400000001").hasSize(3);

        // El modo se ve en el perfil (el frontend adapta las etiquetas)
        assertThat(JsonPath.<String>read(getJson("/api/v1/auth/me"), "$.compania.modoComprobante")).isEqualTo("NINGUNO");
    }

    // ------------------------------------------------------------------ utilidades

    private void modo(String m) throws Exception {
        var res = mvc.perform(put("/api/v1/admin/empresa").header("Authorization", "Bearer " + admin).contentType("application/json")
                .content("{\"nombre\":\"PV Agroinsumos\",\"modoComprobante\":\"" + m + "\"}")).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        assertThat(JsonPath.<String>read(res.getContentAsString(), "$.modoComprobante")).isEqualTo(m);
    }

    private String factura(long cliente) {
        return """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(cliente, urea);
    }

    private String post(String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + admin).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private String getJson(String url) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(res.getStatus()).as("GET %s -> %s", url, res.getContentAsString()).isEqualTo(200);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private static String code(String json) { return JsonPath.read(json, "$.code"); }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }
}
