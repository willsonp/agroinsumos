package com.agroinsumos.backend.compra;

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
 * Cuentas por pagar: un pago abona varias compras de un suplidor, el comprobante muestra cuánto quedó en cada una y
 * lo que se le sigue debiendo (fotografía del momento), y la opción "Pagos a suplidores" basta para pagar.
 */
class PagosSuplidoresIntegrationTest extends IntegrationTestBase {

    String admin, tesorero;
    long proveedor, urea;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long t = usuarioLegacy("tesorero", "tesoreroclave", 'S');
        permitir(t, "CXP_PAGOS");
        admin = login("admin", "adminclave");
        tesorero = login("tesorero", "tesoreroclave");
        post(admin, "/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post(admin, "/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post(admin, "/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Urea","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":4,"costo":1000,"precioContado":1500}"""
                .formatted(depto, unidad), 201));
        proveedor = id(post(admin, "/api/v1/catalogos/proveedores", "{\"nombre\":\"Agrocaste\",\"rncCedula\":\"131000002\"}", 201));
    }

    @Test
    void pagoQueAbonaVariasComprasConComprobante() throws Exception {
        long c1 = id(compra("B0100000001", 1));   // 1,000 (exento)
        long c2 = id(compra("B0100000002", 2));   // 2,000
        // Antigüedad: el suplidor debe 3,000 (corriente)
        assertThat(num(getJson(tesorero, "/api/v1/cxp/antiguedad"), "$[0].total")).isEqualTo(3000.0);
        // El tesorero (solo "Pagos a suplidores") paga 1,500 por transferencia: salda la primera y abona 500 a la segunda
        String pago = post(tesorero, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"concepto":"Abono semanal","aplicaciones":[{"compraId":%d,"monto":1000},{"compraId":%d,"monto":500}],
                 "formas":[{"forma":"TRANSFERENCIA","monto":1500,"referencia":"TRX-9","banco":"BHD"}]}""".formatted(proveedor, c1, c2), 201);
        String imp = getJson(tesorero, "/api/v1/cxp/pagos/" + id(pago) + "/impresion");
        assertThat(JsonPath.<String>read(imp, "$.proveedor")).isEqualTo("AGROCASTE");
        assertThat(JsonPath.<String>read(imp, "$.atendio")).isEqualTo("tesorero");
        assertThat(num(imp, "$.total")).isEqualTo(1500.0);
        List<Map<String, Object>> docs = JsonPath.read(imp, "$.documentos");
        assertThat(docs.get(0)).containsEntry("saldado", true).containsEntry("ncfProveedor", "B0100000001");
        assertThat(((Number) docs.get(1).get("balanceDespues")).doubleValue()).isEqualTo(1500.0);
        assertThat(num(imp, "$.balanceProveedor")).isEqualTo(1500.0);
        // Un pago posterior no cambia el comprobante anterior
        post(tesorero, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":1500}],"formas":[{"forma":"CHEQUE","monto":1500,"referencia":"001"}]}"""
                .formatted(proveedor, c2), 201);
        assertThat(num(getJson(tesorero, "/api/v1/cxp/pagos/" + id(pago) + "/impresion"), "$.balanceProveedor")).isEqualTo(1500.0);
        assertThat(JsonPath.<List<?>>read(getJson(tesorero, "/api/v1/cxp/proveedores/" + proveedor + "/pendientes"), "$")).isEmpty();
        // Sin la opción no se paga
        long otro = usuarioLegacy("cajero", "cajeroclave", 'U');
        permitir(otro, "VENTAS_POS");
        pedir(login("cajero", "cajeroclave"), "/api/v1/cxp/antiguedad", 403);
    }

    // ------------------------------------------------------------------ utilidades

    private String compra(String ncf, int sacos) throws Exception {
        return post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"%s","condicion":"CREDITO","diasCredito":30,
                 "lineas":[{"articuloId":%d,"cantidad":%d,"costoUnitario":1000}]}""".formatted(proveedor, ncf, urea, sacos), 201);
    }

    private String post(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private String pedir(String token, String url, int esperado) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).as("GET %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private String getJson(String token, String url) throws Exception { return pedir(token, url, 200); }

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }
}
