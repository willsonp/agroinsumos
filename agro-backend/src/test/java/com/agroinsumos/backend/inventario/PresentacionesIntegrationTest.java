package com.agroinsumos.backend.inventario;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Presentaciones: la urea se lleva en libras y se compra/vende también por saco de 100 lb. El inventario siempre
 * en libras; la factura y el e-CF muestran lo vendido (1 Saco a su precio); devoluciones y reportes convierten.
 */
class PresentacionesIntegrationTest extends IntegrationTestBase {

    String admin;
    long urea, pala, prov, cliente, libra, depto;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        for (int t : new int[]{31, 32, 34}) {
            post("/api/v1/ecf/secuencias", "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        libra = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Libra\"}", 201));
        long saco = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        // Libra a RD$25 + ITBIS; el saco tiene su propio precio (RD$2,200, más barato que 100 × 25)
        String u = post("/api/v1/inventario/articulos", """
                {"nombre":"Urea 46%%","codigoBarra":"UREA-LB","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":20,
                 "precioContado":25,"unidadEmpaqueId":%d,"factorEmpaque":100,"precioEmpaqueContado":2200,"codigoBarraEmpaque":"UREA-SACO"}"""
                .formatted(depto, libra, saco), 201);
        urea = id(u);
        assertThat(JsonPath.<String>read(u, "$.unidad")).isEqualTo("Libra");
        assertThat(JsonPath.<String>read(u, "$.unidadEmpaque")).isEqualTo("Saco");
        assertThat(JsonPath.<Number>read(u, "$.precioEmpaqueContadoConItbis").doubleValue()).isEqualTo(2596.0);
        assertThat(JsonPath.<Object>read(u, "$.precioEmpaqueCreditoConItbis")).isNull();   // sin precio propio: base × factor
        pala = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Pala","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":200,"precioContado":400}"""
                .formatted(depto, libra), 201));
        prov = id(post("/api/v1/catalogos/proveedores", "{\"nombre\":\"Fertilizantes del Cibao\",\"rncCedula\":\"131000002\"}", 201));
        cliente = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":30}""", 201));
    }

    @Test
    void compraYVentaPorSacoYPorLibra() throws Exception {
        // Validaciones de la ficha
        long caja = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Caja\"}", 201));
        assertThat(code(post("/api/v1/inventario/articulos", """
                {"nombre":"Mal","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1,"precioContado":2,"unidadEmpaqueId":%d}"""
                .formatted(depto, libra, caja), 422))).isEqualTo("FACTOR_REQUERIDO");
        assertThat(code(post("/api/v1/inventario/articulos", """
                {"nombre":"Mal","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1,"precioContado":2,
                 "unidadEmpaqueId":%d,"factorEmpaque":12,"codigoBarraEmpaque":"UREA-LB"}""".formatted(depto, libra, caja), 409)))
                .isEqualTo("CODIGO_BARRA_DUPLICADO");

        // Compra de 5 sacos a RD$2,000: entran 500 lb a RD$20
        String c = post("/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000001","condicion":"CONTADO",
                 "lineas":[{"articuloId":%d,"cantidad":5,"costoUnitario":2000,"empaque":true}]}""".formatted(prov, urea), 201);
        assertThat(JsonPath.<Number>read(c, "$.lineas[0].factor").doubleValue()).isEqualTo(100.0);
        assertThat(existencia(urea)).isEqualTo(500.0);
        assertThat(JsonPath.<Number>read(getJson("/api/v1/inventario/articulos/" + urea), "$.costoPromedio").doubleValue()).isEqualTo(20.0);
        assertThat(code(post("/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000002","condicion":"CONTADO",
                 "lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":200,"empaque":true}]}""".formatted(prov, pala), 422)))
                .isEqualTo("EMPAQUE_NO_DEFINIDO");

        // Venta: 1 saco (precio del saco) + 10 libras
        String f = post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1,"empaque":true},{"articuloId":%d,"cantidad":10}]}"""
                .formatted(cliente, urea, urea), 201);
        List<Map<String, Object>> lineas = JsonPath.read(f, "$.lineas");
        assertThat(lineas.get(0)).containsEntry("unidad", "Saco");
        assertThat(((Number) lineas.get(0).get("precioUnitario")).doubleValue()).isEqualTo(2200.0);
        assertThat(((Number) lineas.get(0).get("factor")).doubleValue()).isEqualTo(100.0);
        assertThat(lineas.get(1)).containsEntry("unidad", "Libra");
        assertThat(JsonPath.<Number>read(f, "$.total").doubleValue()).isEqualTo(2891.0);   // (2200 + 250) × 1.18
        assertThat(existencia(urea)).isEqualTo(390.0);                                     // 500 − 100 − 10
        Double costoSaco = jdbc.queryForObject("select costo_unitario::float8 from factura_linea where factura_id = ? and linea = 1",
                Double.class, id(f));
        assertThat(costoSaco).isEqualTo(2000.0);

        // Devolución del saco: vuelven 100 lb
        post("/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Saco roto","lineas":[{"facturaLineaId":%d,"cantidad":1}]}"""
                .formatted(id(f), ((Number) lineas.get(0).get("id")).longValue()), 201);
        assertThat(existencia(urea)).isEqualTo(490.0);

        // El reporte de utilidad cuenta en libras: 10 lb netas (el saco se devolvió)
        String ut = getJson("/api/v1/reportes/utilidad-por-articulo");
        assertThat(((Number) JsonPath.<List<Map<String, Object>>>read(ut, "$.filas").getFirst().get("cantidad")).doubleValue()).isEqualTo(10.0);

        // Cotización por saco y su factura conservan la presentación
        long cot = id(post("/api/v1/ventas/cotizaciones", """
                {"clienteId":%d,"lineas":[{"articuloId":%d,"cantidad":2,"empaque":true}]}""".formatted(cliente, urea), 201));
        String fc = post("/api/v1/ventas/cotizaciones/" + cot + "/facturar", "{\"condicion\":\"CONTADO\"}", 201);
        assertThat(JsonPath.<Number>read(fc, "$.lineas[0].factor").doubleValue()).isEqualTo(100.0);
        assertThat(existencia(urea)).isEqualTo(290.0);

        // El código de barras del saco encuentra el artículo (el punto de venta lo agrega como saco)
        assertThat(JsonPath.<String>read(getJson("/api/v1/inventario/articulos/codigo/UREA-SACO"), "$.nombre")).isEqualTo("Urea 46%");
        assertThat(code(post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1,"empaque":true}]}""".formatted(cliente, pala), 422)))
                .isEqualTo("EMPAQUE_NO_DEFINIDO");
    }

    // ------------------------------------------------------------------ utilidades

    private double existencia(long articulo) throws Exception {
        return JsonPath.<Number>read(getJson("/api/v1/inventario/articulos/" + articulo), "$.existencia").doubleValue();
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
