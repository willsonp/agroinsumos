package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Motor de reportes: cada reporte corre y los números cuadran con las operaciones. */
class ReportesIntegrationTest extends IntegrationTestBase {

    String admin, supInventario;
    long urea, finca, vendedor, proveedor;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long sup = usuarioLegacy("super", "superclave", 'S');
        permitir(sup, "REP_INVENTARIO");
        admin = login("admin", "adminclave");
        supInventario = login("super", "superclave");
        for (int t : new int[]{31, 34}) {
            post("/api/v1/ecf/secuencias", "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Urea 46%%","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,
                 "precioContado":2100,"precioCredito":2100,"stockMinimo":20}""".formatted(depto, unidad), 201));
        long prov = id(post("/api/v1/catalogos/proveedores", "{\"nombre\":\"Fertilizantes del Cibao\",\"rncCedula\":\"131000002\"}", 201));
        finca = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CREDITO","limiteCredito":0,"diasCredito":30}""", 201));
        vendedor = id(post("/api/v1/catalogos/vendedores", "{\"nombre\":\"Pedro Vendedor\",\"comision\":5}", 201));
        long compra = id(post("/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000001","condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":10,"costoUnitario":1000}]}"""
                .formatted(prov, urea), 201));
        proveedor = prov;
        post("/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":5000}],"formas":[{"forma":"TRANSFERENCIA","monto":5000,"referencia":"TRX-7"}]}"""
                .formatted(prov, compra), 201);
        // Crédito 2 sacos y contado 1 saco (cobrado en efectivo); devolución de 1 saco de la de crédito
        String credito = post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","vendedorId":%d,"lineas":[{"articuloId":%d,"cantidad":2}]}""".formatted(finca, vendedor, urea), 201);
        long contado = id(post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","vendedorId":%d,"lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(finca, vendedor, urea), 201));
        post("/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);
        post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":2478}],"pagos":[{"forma":"EFECTIVO","monto":2500}]}"""
                .formatted(finca, contado), 201);
        post("/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Saco roto","lineas":[{"facturaLineaId":%d,"cantidad":1}]}"""
                .formatted(id(credito), JsonPath.<Integer>read(credito, "$.lineas[0].id")), 201);
    }

    @Test
    void todosLosReportesCorrenYCuadran() throws Exception {
        List<String> codigos = JsonPath.read(getJson(admin, "/api/v1/reportes"), "$[*].codigo");
        assertThat(codigos).hasSize(23);
        for (String c : codigos) getJson(admin, "/api/v1/reportes/" + c + "?proveedorId=" + proveedor);   // todos ejecutan sin error

        String vf = getJson(admin, "/api/v1/reportes/ventas-por-fecha");
        assertThat(total(vf, "total")).isEqualTo(7434.0);          // 4956 + 2478
        assertThat(total(vf, "devoluciones")).isEqualTo(2478.0);
        assertThat(total(vf, "neto")).isEqualTo(4956.0);

        String ut = getJson(admin, "/api/v1/reportes/utilidad-por-articulo");
        Map<String, Object> fila = JsonPath.<List<Map<String, Object>>>read(ut, "$.filas").getFirst();
        assertThat(((Number) fila.get("cantidad")).doubleValue()).isEqualTo(2.0);   // 3 vendidos - 1 devuelto
        assertThat(((Number) fila.get("venta")).doubleValue()).isEqualTo(4200.0);
        assertThat(((Number) fila.get("costo")).doubleValue()).isEqualTo(2000.0);
        assertThat(total(ut, "margen")).isEqualTo(52.38);

        String com = getJson(admin, "/api/v1/reportes/comisiones-vendedor?vendedorId=" + vendedor);
        assertThat(total(com, "base")).isEqualTo(4200.0);           // 6300 - 2100 devuelto (sin ITBIS)
        assertThat(total(com, "comision")).isEqualTo(210.0);

        assertThat(total(getJson(admin, "/api/v1/reportes/cobros-por-fecha"), "efectivo")).isEqualTo(2478.0);   // neto de devuelta
        assertThat(total(getJson(admin, "/api/v1/reportes/compras-por-suplidor"), "total")).isEqualTo(11800.0);
        String inv = getJson(admin, "/api/v1/reportes/inventario-valorizado?conExistencia=true");
        assertThat(total(inv, "existencia")).isEqualTo(8.0);        // 10 - 3 + 1
        assertThat(total(inv, "valor_costo")).isEqualTo(8000.0);
        assertThat(JsonPath.<List<?>>read(getJson(admin, "/api/v1/reportes/bajo-minimo"), "$.filas")).hasSize(1);
        assertThat(JsonPath.<String>read(vf, "$.subtitulo")).startsWith("Del ");

        // Estado de cuenta del suplidor: saldo anterior, compra (cargo), pago (abono) y saldo corrido
        assertThat(JsonPath.<String>read(consultar(admin, "/api/v1/reportes/estado-cuenta-suplidor", 422), "$.code")).isEqualTo("PROVEEDOR_REQUERIDO");
        String ec = getJson(admin, "/api/v1/reportes/estado-cuenta-suplidor?proveedorId=" + proveedor);
        assertThat(JsonPath.<List<String>>read(ec, "$.filas[*].documento")).containsExactly("Saldo anterior", "Compra 1 · B0100000001", "Pago 1 · transferencia TRX-7");
        assertThat(JsonPath.<List<Number>>read(ec, "$.filas[*].saldo")).extracting(Number::doubleValue).containsExactly(0.0, 11800.0, 6800.0);
        assertThat(total(ec, "cargo")).isEqualTo(11800.0);
        assertThat(total(ec, "abono")).isEqualTo(5000.0);
        // Un período posterior arrastra el saldo anterior
        String ec2 = getJson(admin, "/api/v1/reportes/estado-cuenta-suplidor?proveedorId=" + proveedor + "&desde=2099-01-01&hasta=2099-01-31");
        assertThat(JsonPath.<List<Number>>read(ec2, "$.filas[*].saldo")).extracting(Number::doubleValue).containsExactly(6800.0);
        assertThat(total(getJson(admin, "/api/v1/reportes/saldos-suplidores"), "saldo")).isEqualTo(6800.0);

        // Existencia por almacén: hoy coincide con el inventario valorizado; antes del primer movimiento es 0
        assertThat(total(getJson(admin, "/api/v1/reportes/existencia-articulos"), "existencia"))
                .isEqualTo(total(getJson(admin, "/api/v1/reportes/inventario-valorizado"), "existencia"));
        assertThat(total(getJson(admin, "/api/v1/reportes/existencia-articulos?corte=2000-01-01"), "existencia")).isZero();
        String toma = getJson(admin, "/api/v1/reportes/toma-inventario?conExistencia=true");
        assertThat(JsonPath.<List<String>>read(toma, "$.filas[*].fisico")).allMatch(f -> f.startsWith("___"));

        // CSV para Excel
        var csv = mvc.perform(get("/api/v1/reportes/ventas-por-fecha/csv").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        String texto = csv.getContentAsString(StandardCharsets.UTF_8);
        assertThat(texto).startsWith("﻿Ventas resumidas por fecha").contains("Fecha,Facturas,Contado").contains("TOTAL");
        // Inyección de fórmulas: un nombre que empieza con "=" sale como texto en Excel
        jdbc.update("update proveedor set nombre = '=1+1 Cibao' where id = ?", proveedor);
        String csvSaldos = mvc.perform(get("/api/v1/reportes/saldos-suplidores/csv").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csvSaldos).contains("'=1+1 Cibao").doesNotContain(",=1+1");

        // Rango inválido
        assertThat(JsonPath.<String>read(consultar(admin, "/api/v1/reportes/ventas-por-fecha?desde=2026-02-01&hasta=2026-01-01", 422), "$.code"))
                .isEqualTo("RANGO_INVALIDO");
    }

    @Test
    void cadaGrupoRequiereSuOpcion() throws Exception {
        List<String> grupos = JsonPath.read(getJson(supInventario, "/api/v1/reportes"), "$[*].grupo");
        assertThat(grupos).isNotEmpty().containsOnly("INVENTARIO");
        consultar(supInventario, "/api/v1/reportes/inventario-valorizado", 200);
        consultar(supInventario, "/api/v1/reportes/utilidad-por-articulo", 403);
    }

    // ------------------------------------------------------------------ helpers
    private static double total(String json, String clave) {
        return JsonPath.<Number>read(json, "$.totales." + clave).doubleValue();
    }

    private String post(String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + admin).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString();
    }

    private String consultar(String token, String url, int esperado) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).as("GET %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private String getJson(String token, String url) throws Exception { return consultar(token, url, 200); }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }
}
