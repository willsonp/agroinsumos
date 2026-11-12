package com.agroinsumos.backend.venta;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Ticket POS (datos de impresión, original/reimpresión, lema y pie de la empresa) y venta a crédito con los días
 * elegidos al facturar (como el sistema anterior: el cliente no tiene días configurados).
 */
class TicketYCreditoIntegrationTest extends IntegrationTestBase {

    String admin;
    long urea, cliente;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        for (int t : new int[]{31, 32}) {
            post("/api/v1/ecf/secuencias", "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Urea","tipo":"S","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":400,"precioContado":650}"""
                .formatted(depto, unidad), 201));
        cliente = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Juan Pérez","telefono":"809-000-0000","direccion":"La Vega","tipoEcfPreferido":32,"tipoPrecio":"CONTADO",
                 "limiteCredito":0,"diasCredito":0}""", 201));
    }

    @Test
    void ticketYCreditoPorDias() throws Exception {
        var res = mvc.perform(put("/api/v1/admin/empresa").header("Authorization", "Bearer " + admin).contentType("application/json")
                .content("{\"nombre\":\"Negocio Demo SRL\",\"telefono\":\"809-000-5000\",\"lema\":\"Donde tu dinero rinde más\","
                        + "\"pieTicket\":\"¡Comprometidos con tu satisfacción!\"}")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        String me = getJson("/api/v1/auth/me");
        assertThat(JsonPath.<String>read(me, "$.compania.lema")).isEqualTo("Donde tu dinero rinde más");
        assertThat(JsonPath.<String>read(me, "$.compania.pieTicket")).isEqualTo("¡Comprometidos con tu satisfacción!");
        assertThat(JsonPath.<String>read(me, "$.compania.telefono")).isEqualTo("809-000-5000");

        // Crédito: sin días en el request ni en el cliente se rechaza; con días elegidos al facturar se acepta
        assertThat(code(post("/api/v1/ventas/facturas", factura(null), 422))).isEqualTo("SIN_CREDITO");
        String f = post("/api/v1/ventas/facturas", factura(15), 201);
        assertThat(JsonPath.<String>read(f, "$.condicion")).isEqualTo("CREDITO");
        assertThat(JsonPath.<Integer>read(f, "$.diasCredito")).isEqualTo(15);
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Santo_Domingo"));
        assertThat(JsonPath.<String>read(f, "$.fechaVencimiento")).isEqualTo(hoy.plusDays(15).toString());
        assertThat(JsonPath.<Number>read(f, "$.balance").doubleValue()).isEqualTo(767.0);

        // Ticket: contacto del cliente, quién atendió, firma del e-CF; original y luego reimpresión
        String t = getJson("/api/v1/ventas/facturas/" + id(f) + "/ticket");
        assertThat(JsonPath.<String>read(t, "$.clienteTelefono")).isEqualTo("809-000-0000");
        assertThat(JsonPath.<String>read(t, "$.clienteDireccion")).isEqualTo("La Vega");
        assertThat(JsonPath.<String>read(t, "$.atendio")).isEqualTo("admin");
        assertThat(JsonPath.<String>read(t, "$.fechaFirma")).isNotNull();
        assertThat(JsonPath.<Integer>read(t, "$.impresiones")).isZero();
        assertThat(JsonPath.<Integer>read(post("/api/v1/ventas/facturas/" + id(f) + "/ticket/impresion", "", 200), "$.impresiones")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(post("/api/v1/ventas/facturas/" + id(f) + "/ticket/impresion", "", 200), "$.impresiones")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(getJson("/api/v1/ventas/facturas/" + id(f) + "/ticket"), "$.impresiones")).isEqualTo(2);
        var noExiste = mvc.perform(get("/api/v1/ventas/facturas/999999/ticket").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(noExiste.getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ utilidades

    private String factura(Integer dias) {
        return """
                {"clienteId":%d,"condicion":"CREDITO",%s"lineas":[{"articuloId":%d,"cantidad":1}]}"""
                .formatted(cliente, dias == null ? "" : "\"diasCredito\":" + dias + ",", urea);
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
