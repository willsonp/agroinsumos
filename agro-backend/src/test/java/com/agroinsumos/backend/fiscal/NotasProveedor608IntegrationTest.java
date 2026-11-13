package com.agroinsumos.backend.fiscal;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Notas de crédito/débito de suplidores (CxP, inventario, 606, contabilidad) y formato 608. */
class NotasProveedor608IntegrationTest extends IntegrationTestBase {

    String admin;
    long urea, proveedor, finca;
    final LocalDate hoy = LocalDate.now(ZoneId.of("America/Santo_Domingo"));
    final String periodo = hoy.format(DateTimeFormatter.ofPattern("yyyyMM"));

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        post(admin, "/api/v1/ecf/secuencias", "{\"tipoEcf\":31,\"secuenciaDesde\":1,\"secuenciaHasta\":50,\"fechaVencimiento\":\"2030-12-31\"}", 201);
        post(admin, "/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post(admin, "/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post(admin, "/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Urea 46%%","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,
                 "precioContado":2100,"precioCredito":2100}""".formatted(depto, unidad), 201));
        proveedor = id(post(admin, "/api/v1/catalogos/proveedores", "{\"nombre\":\"Fertilizantes del Cibao\",\"rncCedula\":\"131000002\"}", 201));
        finca = id(post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CREDITO","limiteCredito":1000000,"diasCredito":30}""", 201));
    }

    @Test
    void notasDeSuplidorAjustanCxpInventario606YContabilidad() throws Exception {
        String compra = post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000010","condicion":"CREDITO",
                 "lineas":[{"articuloId":%d,"cantidad":10,"costoUnitario":1000}]}""".formatted(proveedor, urea), 201);
        long compraId = id(compra);
        long linea = JsonPath.<Number>read(compra, "$.lineas[0].id").longValue();

        String nc = """
                {"tipo":"CREDITO","compraId":%d,"ncf":"%s","concepto":"Devolución sacos rotos","lineas":[{"compraLineaId":%d,"cantidad":%s}]}""";
        assertThat(code(post(admin, "/api/v1/cxp/notas", nc.formatted(compraId, "B0300000001", linea, "2"), 422))).isEqualTo("NCF_TIPO_INCORRECTO");
        String n1 = post(admin, "/api/v1/cxp/notas", nc.formatted(compraId, "B0400000001", linea, "2"), 201);
        assertThat(num(n1, "$.total")).isEqualTo(2360.0);
        assertThat(num(n1, "$.balanceCompra")).isEqualTo(11800 - 2360.0);
        assertThat(num(getJson("/api/v1/inventario/articulos/" + urea), "$.existencia")).isEqualTo(8.0);
        assertThat(code(post(admin, "/api/v1/cxp/notas", nc.formatted(compraId, "B0400000002", linea, "9"), 422))).isEqualTo("CANTIDAD_EXCEDE");
        assertThat(code(post(admin, "/api/v1/cxp/notas", nc.formatted(compraId, "B0400000001", linea, "1"), 409))).isEqualTo("NCF_DUPLICADO");

        String nd = post(admin, "/api/v1/cxp/notas", """
                {"tipo":"DEBITO","compraId":%d,"ncf":"B0300000005","concepto":"Cargo por flete","subtotal":500,"itbis":90}""".formatted(compraId), 201);
        assertThat(num(nd, "$.balanceCompra")).isEqualTo(9440 + 590.0);
        assertThat(code(post(admin, "/api/v1/compras/" + compraId + "/anular", "", 422))).isEqualTo("COMPRA_CON_NOTAS");

        // 606: compra + 2 notas con el NCF de la compra como modificado
        String f606 = getJson("/api/v1/fiscal/606?periodo=" + periodo);
        assertThat(JsonPath.<List<String>>read(f606, "$.lineas[?(@.origen == 'NOTA_PROVEEDOR')].ncfModificado")).containsOnly("B0100000010");
        assertThat(JsonPath.<List<String>>read(f606, "$.lineas[?(@.origen == 'NOTA_PROVEEDOR')].formaPago")).containsExactlyInAnyOrder("06", "04");

        // Contabilidad: las notas se contabilizan y la balanza cuadra
        post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 200);
        String gen = post(admin, "/api/v1/contabilidad/asientos/generar?desde=%s&hasta=%s".formatted(hoy.withDayOfMonth(1), hoy), "", 200);
        assertThat(JsonPath.<List<String>>read(gen, "$.porOrigen[*].origenTipo")).contains("NOTA_PROVEEDOR");
        assertThat(JsonPath.<List<?>>read(gen, "$.errores")).isEmpty();
        assertThat(JsonPath.<Boolean>read(getJson("/api/v1/contabilidad/reportes/balanza?desde=%s&hasta=%s"
                .formatted(hoy.withDayOfMonth(1), hoy)), "$.cuadra")).isTrue();

        // Pagada la deuda, la nota de débito ya no se anula; la de crédito sí (reingresa la mercancía)
        post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":10030}],"formas":[{"forma":"TRANSFERENCIA","monto":10030}]}"""
                .formatted(proveedor, compraId), 201);
        assertThat(code(post(admin, "/api/v1/cxp/notas/" + id(nd) + "/anular", "{\"motivo\":\"x\"}", 422))).isEqualTo("NOTA_PAGADA");
        String an = post(admin, "/api/v1/cxp/notas/" + id(n1) + "/anular", "{\"motivo\":\"El suplidor la retiró\"}", 200);
        assertThat(num(an, "$.balanceCompra")).isEqualTo(2360.0);
        assertThat(num(getJson("/api/v1/inventario/articulos/" + urea), "$.existencia")).isEqualTo(10.0);
        assertThat(num(post(admin, "/api/v1/contabilidad/asientos/generar?desde=%s&hasta=%s".formatted(hoy.withDayOfMonth(1), hoy), "", 200),
                "$.reversos")).isEqualTo(1.0);
    }

    @Test
    void formato608ConComprobantesAnulados() throws Exception {
        String anul = """
                {"ncf":"%s","fechaComprobante":"%s","tipoAnulacion":"%s","motivo":"Talonario dañado"}""";
        assertThat(code(post(admin, "/api/v1/fiscal/anulaciones", anul.formatted("B01123", hoy, "01"), 422))).isEqualTo("NCF_INVALIDO");
        post(admin, "/api/v1/fiscal/anulaciones", anul.formatted("B0100000099", hoy, "03"), 201);
        assertThat(code(post(admin, "/api/v1/fiscal/anulaciones", anul.formatted("B0100000099", hoy, "03"), 409))).isEqualTo("NCF_YA_ANULADO");
        assertThat(code(post(admin, "/api/v1/fiscal/anulaciones", anul.formatted("B0100000098", hoy, "11"), 400))).isEqualTo("VALIDACION");

        // Un comprobante usado en una factura vigente no se anula
        post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":5,"costoUnitario":1000}]}"""
                .formatted(proveedor, urea), 201);
        String f = post(admin, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(finca, urea), 201);
        assertThat(code(post(admin, "/api/v1/fiscal/anulaciones",
                anul.formatted(JsonPath.<String>read(f, "$.encf"), hoy, "04"), 422))).isIn("NCF_EN_USO", "ECF_ACEPTADO");

        String rep = getJson("/api/v1/fiscal/608?periodo=" + periodo);
        assertThat(JsonPath.<List<String>>read(rep, "$.lineas[*].ncf")).containsExactly("B0100000099");
        var txt = mvc.perform(get("/api/v1/fiscal/608/txt?periodo=" + periodo).header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        assertThat(txt).isEqualTo("608|131000001|%s|1\r\nB0100000099|%s|03\r\n".formatted(periodo, hoy.format(DateTimeFormatter.BASIC_ISO_DATE)));
    }

    // ------------------------------------------------------------------ helpers
    private String post(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body))
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

    private static String code(String json) {
        try { return JsonPath.read(json, "$.code"); } catch (Exception e) { return null; }
    }
}
