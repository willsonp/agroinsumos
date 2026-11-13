package com.agroinsumos.backend.venta;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Límite de crédito: con límite 15,000 y deuda de 10,000, solo se puede facturar a crédito hasta 5,000. La deuda es
 * todo lo pendiente (facturas a crédito y de contado sin cobrar), igual que en Cobros.
 */
class LimiteCreditoIntegrationTest extends IntegrationTestBase {

    String admin;
    long abono, cliente;

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
        // Servicio exento a RD$1,000 (sin inventario ni ITBIS: montos redondos)
        abono = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Fumigación","tipo":"S","departamentoId":%d,"unidadId":%d,"indicadorItbis":4,"costo":0,"precioContado":1000}"""
                .formatted(depto, unidad), 201));
        cliente = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Juan Pérez","tipoEcfPreferido":32,"tipoPrecio":"CONTADO","limiteCredito":15000,"diasCredito":15}""", 201));
    }

    @Test
    void noFacturaACreditoMasDelDisponible() throws Exception {
        post("/api/v1/ventas/facturas", factura("CREDITO", 10), 201);                 // debe 10,000
        String c = getJson("/api/v1/catalogos/clientes/" + cliente + "/credito");
        assertThat(num(c, "$.deuda")).isEqualTo(10000.0);
        assertThat(num(c, "$.disponible")).isEqualTo(5000.0);

        // 6,000 excede lo disponible (5,000): no se emite (ni consume NCF)
        long antes = jdbc.queryForObject("select count(*) from factura", Long.class);
        String err = post("/api/v1/ventas/facturas", factura("CREDITO", 6), 422);
        assertThat(JsonPath.<String>read(err, "$.code")).isEqualTo("LIMITE_CREDITO_EXCEDIDO");
        assertThat(JsonPath.<String>read(err, "$.detail")).contains("disponible RD$5000");
        assertThat(jdbc.queryForObject("select count(*) from factura", Long.class)).isEqualTo(antes);

        // Justo lo disponible sí
        post("/api/v1/ventas/facturas", factura("CREDITO", 5), 201);
        assertThat(num(getJson("/api/v1/catalogos/clientes/" + cliente + "/credito"), "$.disponible")).isZero();
        assertThat(code(post("/api/v1/ventas/facturas", factura("CREDITO", 1), 422))).isEqualTo("LIMITE_CREDITO_EXCEDIDO");

        // Abona 2,000: vuelve a tener 2,000 disponibles
        post("/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);
        long f1 = jdbc.queryForObject("select min(id) from factura", Long.class);
        post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":2000}],
                 "pagos":[{"forma":"EFECTIVO","monto":2000}]}""".formatted(cliente, f1), 201);
        assertThat(num(getJson("/api/v1/catalogos/clientes/" + cliente + "/credito"), "$.disponible")).isEqualTo(2000.0);

        // Una factura de contado que quedó sin cobrar también es deuda: consume lo disponible
        post("/api/v1/ventas/facturas", factura("CONTADO", 2), 201);
        assertThat(num(getJson("/api/v1/catalogos/clientes/" + cliente + "/credito"), "$.deuda")).isEqualTo(15000.0);
        assertThat(code(post("/api/v1/ventas/facturas", factura("CREDITO", 1), 422))).isEqualTo("LIMITE_CREDITO_EXCEDIDO");

        // Las ventas de contado pagadas no tienen límite
        post("/api/v1/caja/ventas", """
                {"factura":%s,"pagos":[{"forma":"EFECTIVO","monto":20000}]}""".formatted(factura("CONTADO", 20)), 201);
    }

    // ------------------------------------------------------------------ utilidades

    private String factura(String condicion, int cantidad) {
        return """
                {"clienteId":%d,"condicion":"%s","lineas":[{"articuloId":%d,"cantidad":%d}]}""".formatted(cliente, condicion, abono, cantidad);
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

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }
}
