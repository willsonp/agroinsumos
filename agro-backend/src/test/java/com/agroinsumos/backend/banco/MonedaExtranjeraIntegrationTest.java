package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Tasas de cambio, compras y pagos en dólares con diferencia cambiaria, cuentas en USD y descuentos en ventas. */
class MonedaExtranjeraIntegrationTest extends IntegrationTestBase {

    String admin;
    long urea, proveedor, finca, cuentaUsd, cuentaDop;
    final LocalDate hoy = LocalDate.now(ZoneId.of("America/Santo_Domingo"));
    final String rango = "desde=" + hoy.withDayOfMonth(1) + "&hasta=" + hoy;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        post("/api/v1/ecf/secuencias", "{\"tipoEcf\":31,\"secuenciaDesde\":1,\"secuenciaHasta\":50,\"fechaVencimiento\":\"2030-12-31\"}", 201);
        post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Urea 46%%","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,
                 "precioContado":2100,"precioCredito":2100}""".formatted(depto, unidad), 201));
        proveedor = id(post("/api/v1/catalogos/proveedores", "{\"nombre\":\"Yara International\",\"rncCedula\":\"131000002\"}", 201));
        finca = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CREDITO","limiteCredito":0,"diasCredito":30}""", 201));
        long banco = id(post("/api/v1/banco/bancos", "{\"nombre\":\"BHD\"}", 201));
        cuentaUsd = id(post("/api/v1/banco/cuentas", """
                {"bancoId":%d,"numero":"USD-1","nombre":"Dólares","moneda":"USD","saldoInicial":5000,"fechaSaldoInicial":"2020-01-01"}""".formatted(banco), 201));
        cuentaDop = id(post("/api/v1/banco/cuentas", """
                {"bancoId":%d,"numero":"DOP-1","nombre":"Pesos","saldoInicial":1000000,"fechaSaldoInicial":"2020-01-01"}""".formatted(banco), 201));
        post("/api/v1/monedas/tasas", "{\"moneda\":\"USD\",\"fecha\":\"%s\",\"tasa\":60}".formatted(hoy), 201);
    }

    @Test
    void compraYPagoEnDolaresConDiferenciaCambiaria() throws Exception {
        assertThat(code(post("/api/v1/monedas/tasas", "{\"moneda\":\"DOP\",\"tasa\":1}", 422))).isEqualTo("MONEDA_INVALIDA");
        assertThat(num(getJson("/api/v1/monedas/tasas/vigente?moneda=USD"), "$.tasa")).isEqualTo(60.0);
        assertThat(code(post("/api/v1/compras", compra("EUR", null, 10, 20), 422))).isEqualTo("TASA_NO_REGISTRADA");

        // Importación: 10 sacos a US$20 con tasa del día de la factura 58.50 -> RD$11,700 (costo RD$1,170)
        String c1 = post("/api/v1/compras", compra("USD", "58.50", 10, 20), 201);
        assertThat(num(c1, "$.total")).isEqualTo(11700.0);
        assertThat(num(c1, "$.totalMoneda")).isEqualTo(200.0);
        assertThat(num(c1, "$.balanceMoneda")).isEqualTo(200.0);
        assertThat(num(getJson("/api/v1/inventario/articulos/" + urea), "$.costoPromedio")).isEqualTo(1170.0);

        // Reglas: moneda del pago = moneda de la compra; en dólares no hay efectivo; cobros en pesos no van a cuentas en USD
        assertThat(code(post("/api/v1/cxp/pagos", pago(id(c1), null, 200, "TRANSFERENCIA", cuentaUsd), 422))).isEqualTo("COMPRA_OTRA_MONEDA");
        assertThat(code(post("/api/v1/cxp/pagos", pago(id(c1), "USD", 200, "EFECTIVO", null), 422))).isEqualTo("FORMA_INVALIDA");

        // Se paga a la tasa de hoy (60): RD$12,000 por una deuda de RD$11,700 -> pérdida cambiaria RD$300
        String p1 = post("/api/v1/cxp/pagos", pago(id(c1), "USD", 200, "TRANSFERENCIA", cuentaUsd), 201);
        assertThat(num(p1, "$.tasaCambio")).isEqualTo(60.0);
        assertThat(num(p1, "$.diferenciaCambiaria")).isEqualTo(300.0);
        String c1b = getJson("/api/v1/compras/" + id(c1));
        assertThat(JsonPath.<String>read(c1b, "$.estado")).isEqualTo("PAGADA");
        assertThat(num(c1b, "$.balance")).isEqualTo(0.0);
        assertThat(num(getJson("/api/v1/banco/cuentas/" + cuentaUsd), "$.saldo")).isEqualTo(4800.0);

        // Abono parcial desde la cuenta en pesos: US$40 a 61 = RD$2,440; la compra (tasa 59) baja RD$2,360
        post("/api/v1/monedas/tasas", "{\"moneda\":\"USD\",\"fecha\":\"%s\",\"tasa\":61}".formatted(hoy), 201);
        long c2 = id(post("/api/v1/compras", compra("USD", "59", 5, 20), 201));
        String p2 = post("/api/v1/cxp/pagos", pago(c2, "USD", 40, "TRANSFERENCIA", cuentaDop), 201);
        assertThat(num(p2, "$.diferenciaCambiaria")).isEqualTo(80.0);
        assertThat(num(getJson("/api/v1/compras/" + c2), "$.balanceMoneda")).isEqualTo(60.0);
        assertThat(num(getJson("/api/v1/compras/" + c2), "$.balance")).isEqualTo(5900 - 2360.0);
        assertThat(num(getJson("/api/v1/banco/cuentas/" + cuentaDop), "$.saldo")).isEqualTo(1000000 - 2440.0);
        post("/api/v1/cxp/pagos/" + id(p2) + "/anular", "{\"motivo\":\"Error\"}", 200);
        assertThat(num(getJson("/api/v1/compras/" + c2), "$.balanceMoneda")).isEqualTo(100.0);
        assertThat(num(getJson("/api/v1/compras/" + c2), "$.balance")).isEqualTo(5900.0);

        // Transferencia de pesos a dólares: RD$61,000 a 61 = US$1,000
        post("/api/v1/banco/transferencias", """
                {"cuentaOrigenId":%d,"cuentaDestinoId":%d,"monto":61000,"concepto":"Compra de divisas"}""".formatted(cuentaDop, cuentaUsd), 201);
        assertThat(num(getJson("/api/v1/banco/cuentas/" + cuentaUsd), "$.saldo")).isEqualTo(5800.0);
        long fac = id(post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(finca, urea), 201));
        assertThat(code(post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":1}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":1,"cuentaBancariaId":%d}]}""".formatted(finca, fac, cuentaUsd), 422)))
                .isEqualTo("CUENTA_OTRA_MONEDA");

        // Contabilidad: diferencia cambiaria realizada y balanza cuadrada
        post("/api/v1/contabilidad/cuentas/plantilla", "", 200);
        String gen = post("/api/v1/contabilidad/asientos/generar?" + rango, "", 200);
        assertThat(JsonPath.<List<?>>read(gen, "$.errores")).isEmpty();
        String pagos = getJson("/api/v1/contabilidad/asientos?origenTipo=PAGO_PROVEEDOR");
        assertThat(JsonPath.<List<String>>read(pagos, "$.content[*].lineas[*].cuentaCodigo")).contains("6104");
        assertThat(num(getJson("/api/v1/contabilidad/reportes/estado-resultados?" + rango), "$.gastos.total")).isEqualTo(300.0);
        assertThat(JsonPath.<List<Number>>read(pagos, "$.content[*].lineas[?(@.cuentaCodigo == '6104')].debito"))
                .extracting(Number::doubleValue).containsExactly(300.0);   // el pago anulado antes de contabilizar no genera asiento
        assertThat(JsonPath.<Boolean>read(getJson("/api/v1/contabilidad/reportes/balanza?" + rango), "$.cuadra")).isTrue();
        // Transferencia DOP -> USD: Dr banco / Cr banco por RD$61,000
        String mov = getJson("/api/v1/contabilidad/asientos?origenTipo=MOV_BANCO");
        assertThat(JsonPath.<List<Number>>read(mov, "$.content[*].total")).extracting(Number::doubleValue).containsExactly(61000.0);
    }

    @Test
    void descuentoEnVentasEnCuentaSeparada() throws Exception {
        post("/api/v1/compras", compra(null, null, 10, 1000), 201);
        // 2 x 2100 con 10%: descuento 420, neto 3780, ITBIS 680.40
        post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":2,"descuentoPorcentaje":10}]}""".formatted(finca, urea), 201);
        post("/api/v1/contabilidad/cuentas/plantilla", "", 200);
        post("/api/v1/contabilidad/asientos/generar?" + rango, "", 200);
        String a = getJson("/api/v1/contabilidad/asientos?origenTipo=FACTURA");
        assertThat(JsonPath.<List<Number>>read(a, "$.content[0].lineas[?(@.cuentaCodigo == '4103')].debito")).extracting(Number::doubleValue).containsExactly(420.0);
        assertThat(JsonPath.<List<Number>>read(a, "$.content[0].lineas[?(@.cuentaCodigo == '4101')].credito")).extracting(Number::doubleValue).containsExactly(4200.0);
        String er = getJson("/api/v1/contabilidad/reportes/estado-resultados?" + rango);
        assertThat(num(er, "$.ingresos.total")).isEqualTo(3780.0);
    }

    // ------------------------------------------------------------------ helpers
    private String compra(String moneda, String tasa, int cantidad, double costo) {
        return """
                {"proveedorId":%d,"condicion":"CREDITO","moneda":%s,"tasaCambio":%s,
                 "lineas":[{"articuloId":%d,"cantidad":%d,"costoUnitario":%s,"tasaItbis":%s}]}"""
                .formatted(proveedor, moneda == null ? "null" : "\"" + moneda + "\"", tasa == null ? "null" : tasa, urea, cantidad, costo,
                        moneda == null ? "18" : "0");
    }

    private String pago(long compraId, String moneda, double monto, String forma, Long cuenta) {
        return """
                {"proveedorId":%d,"moneda":%s,"aplicaciones":[{"compraId":%d,"monto":%s}],
                 "formas":[{"forma":"%s","monto":%s,"cuentaBancariaId":%s}]}"""
                .formatted(proveedor, moneda == null ? "null" : "\"" + moneda + "\"", compraId, monto, forma, monto, cuenta);
    }

    private String post(String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + admin).contentType("application/json").content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString();
    }

    private String getJson(String url) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(res.getStatus()).as("GET %s -> %s", url, res.getContentAsString()).isEqualTo(200);
        return res.getContentAsString();
    }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static String code(String json) { return JsonPath.read(json, "$.code"); }
}
