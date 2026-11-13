package com.agroinsumos.backend.cxc;

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
 * Recibo de ingreso impreso con el balance que quedó en cada factura y del cliente (fotografía del cobro), y entrada
 * de inventario por saco: se guarda en libras y el kárdex informa la presentación para mostrar el costo por saco.
 */
class ReciboImpresionYDocumentosIntegrationTest extends IntegrationTestBase {

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
        long libra = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Libra\"}", 201));
        long saco = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        abono = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Abono 20-5-20","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":4,"costo":0,"precioContado":20,
                 "unidadEmpaqueId":%d,"factorEmpaque":100}""".formatted(depto, libra, saco), 201));
        cliente = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Rosa Los Guallos","tipoEcfPreferido":32,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":30}""", 201));
    }

    @Test
    void entradaPorSacoKardexYReciboConBalances() throws Exception {
        // Entrada: 2 sacos a RD$1,000 el saco = 200 lb a RD$10
        String d = post("/api/v1/inventario/documentos", """
                {"tipo":"ENTRADA","descripcion":"Inicial","lineas":[{"articuloId":%d,"cantidad":2,"costoUnitario":1000,"empaque":true}]}"""
                .formatted(abono), 201);
        assertThat(num(d, "$.lineas[0].cantidad")).isEqualTo(200.0);
        assertThat(num(d, "$.lineas[0].costoUnitario")).isEqualTo(10.0);
        post("/api/v1/inventario/documentos/" + id(d) + "/aplicar", "", 200);
        assertThat(num(getJson("/api/v1/inventario/articulos/" + abono), "$.existencia")).isEqualTo(200.0);
        String k = getJson("/api/v1/inventario/articulos/" + abono + "/kardex/reporte");
        assertThat(JsonPath.<String>read(k, "$.unidad")).isEqualTo("Libra");
        assertThat(JsonPath.<String>read(k, "$.unidadEmpaque")).isEqualTo("Saco");
        assertThat(num(k, "$.factorEmpaque")).isEqualTo(100.0);
        assertThat(num(k, "$.saldoFinal")).isEqualTo(200.0);

        // Dos facturas a crédito: 20 lb (400) y 50 lb (1,000), exentas de ITBIS; deuda 1,400
        long f1 = id(post("/api/v1/ventas/facturas", factura(20), 201));
        long f2 = id(post("/api/v1/ventas/facturas", factura(50), 201));
        post("/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);   // el efectivo entra a la caja
        // Abono de 1,000 distribuido: salda la primera (400) y abona 600 a la segunda (quedan 400)
        String rec = post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":400},
                                                {"documentoTipo":"FACTURA","documentoId":%d,"monto":600}],
                 "pagos":[{"forma":"EFECTIVO","monto":1000}]}""".formatted(cliente, f1, f2), 201);
        String imp = getJson("/api/v1/cxc/recibos/" + id(rec) + "/impresion");
        assertThat(JsonPath.<String>read(imp, "$.clienteNombre")).isEqualTo("ROSA LOS GUALLOS");
        assertThat(JsonPath.<String>read(imp, "$.atendio")).isEqualTo("admin");
        assertThat(num(imp, "$.totalRecibido")).isEqualTo(1000.0);
        List<Map<String, Object>> docs = JsonPath.read(imp, "$.documentos");
        assertThat(docs).hasSize(2);
        assertThat(((Number) docs.get(0).get("balanceDespues")).doubleValue()).isZero();
        assertThat(docs.get(0)).containsEntry("saldado", true);
        assertThat(((Number) docs.get(1).get("balanceDespues")).doubleValue()).isEqualTo(400.0);
        assertThat(docs.get(1)).containsEntry("saldado", false);
        assertThat(num(imp, "$.balanceCliente")).isEqualTo(400.0);

        // Otro abono después: la reimpresión del primer recibo conserva los balances de su momento
        post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":150}],
                 "pagos":[{"forma":"EFECTIVO","monto":150}]}""".formatted(cliente, f2), 201);
        String re = getJson("/api/v1/cxc/recibos/" + id(rec) + "/impresion");
        assertThat(num(re, "$.balanceCliente")).isEqualTo(400.0);
        assertThat(num(re, "$.documentos[1].balanceDespues")).isEqualTo(400.0);
        // Cierre de caja: la venta del punto de venta es "factura a contado"; una factura de contado que se cobra
        // después en Cobros es un recibo de CxC (no se confunde por la condición de la factura)
        post("/api/v1/caja/ventas", """
                {"factura":{"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":5}]},
                 "pagos":[{"forma":"EFECTIVO","monto":100}]}""".formatted(cliente, abono), 201);
        long contadoPendiente = id(post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":3}]}""".formatted(cliente, abono), 201));
        post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"concepto":"Factura pendiente","aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":60}],
                 "pagos":[{"forma":"EFECTIVO","monto":60}]}""".formatted(cliente, contadoPendiente), 201);
        long sesion = id(getJson("/api/v1/caja/sesiones/actual"));
        String cierre = getJson("/api/v1/caja/sesiones/" + sesion + "/cierre");
        assertThat(primero(cierre, "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].cantidad")).isEqualTo(1.0);
        assertThat(primero(cierre, "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].monto")).isEqualTo(100.0);
        assertThat(primero(cierre, "$.conceptos[?(@.codigo=='RECIBOS_CXC')].cantidad")).isEqualTo(3.0);
        assertThat(primero(cierre, "$.conceptos[?(@.codigo=='RECIBOS_CXC')].monto")).isEqualTo(1210.0);   // 1000 + 150 + 60
        assertThat(num(cierre, "$.totalDia.monto")).isEqualTo(1310.0);
        assertThat(JsonPath.<Object>read(cierre, "$.resultado")).isNull();     // abierta: aún no hay conteo
        // Cierra contando 50 de más y declarando 0 en tarjetas: sobrante (diferencia positiva, como el sistema anterior)
        post("/api/v1/caja/sesiones/actual/cerrar", """
                {"conteo":[{"denominacion":1000,"cantidad":1},{"denominacion":200,"cantidad":1},{"denominacion":100,"cantidad":1},
                           {"denominacion":50,"cantidad":1},{"denominacion":10,"cantidad":1}],"tarjetas":0}""", 200);
        String cerrada = getJson("/api/v1/caja/sesiones/" + sesion + "/cierre");
        assertThat(num(cerrada, "$.contado.efectivo")).isEqualTo(1360.0);
        assertThat(num(cerrada, "$.contado.tarjeta")).isEqualTo(0.0);
        assertThat(num(cerrada, "$.diferencia")).isEqualTo(50.0);
        assertThat(JsonPath.<String>read(cerrada, "$.resultado")).isEqualTo("SOBRANTE");

        var noExiste = mvc.perform(get("/api/v1/cxc/recibos/999999/impresion").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(noExiste.getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ utilidades

    private String factura(int libras) {
        return """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":%d}]}""".formatted(cliente, abono, libras);
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

    private static double primero(String json, String path) { return JsonPath.<List<Number>>read(json, path).getFirst().doubleValue(); }

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }
}
