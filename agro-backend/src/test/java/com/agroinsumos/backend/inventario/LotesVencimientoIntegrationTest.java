package com.agroinsumos.backend.inventario;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Lotes con fecha de vencimiento: solo los artículos marcados la exigen en la compra; las salidas descuentan por
 * lote (FEFO), las devoluciones y anulaciones vuelven al lote de origen y el reporte lista lo que está por vencer.
 */
class LotesVencimientoIntegrationTest extends IntegrationTestBase {

    String admin;
    long herbicida, pala, principal, tienda, prov, finca;
    final LocalDate hoy = LocalDate.now(java.time.ZoneId.of("America/Santo_Domingo"));   // la fecha del negocio (hora de RD)

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        for (int t : new int[]{31, 34}) {
            post("/api/v1/ecf/secuencias", "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        principal = id(post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201));
        tienda = id(post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Tienda\"}", 201));
        long unidad = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Litro\"}", 201));
        long depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"Agroquímicos\",\"tipo\":\"I\"}", 201));
        herbicida = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Glifosato 1L","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":300,
                 "precioContado":500,"manejaVencimiento":true}""".formatted(depto, unidad), 201));
        pala = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Pala","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":200,"precioContado":400}"""
                .formatted(depto, unidad), 201));
        prov = id(post("/api/v1/catalogos/proveedores", "{\"nombre\":\"Agroquímicos SA\",\"rncCedula\":\"131000002\"}", 201));
        finca = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":30}""", 201));
    }

    @Test
    void comprasVentasDevolucionesTransferenciasYReporte() throws Exception {
        // La fecha solo se exige al artículo que controla vencimiento; la pala se compra como siempre
        assertThat(code(post("/api/v1/compras", compra("B0100000001", "{\"articuloId\":%d,\"cantidad\":5,\"costoUnitario\":300}".formatted(herbicida)), 422)))
                .isEqualTo("VENCIMIENTO_REQUERIDO");
        assertThat(code(post("/api/v1/compras", compra("B0100000001", linea(herbicida, 5, "L-0", hoy.minusDays(1))), 422)))
                .isEqualTo("LOTE_VENCIDO");
        String c = post("/api/v1/compras", compra("B0100000001",
                linea(herbicida, 10, "l-a", hoy.plusDays(3)) + "," + linea(herbicida, 6, "L-B", hoy.plusDays(40))
                        + ",{\"articuloId\":%d,\"cantidad\":4,\"costoUnitario\":200}".formatted(pala)), 201);
        assertThat(JsonPath.<List<String>>read(c, "$.lineas[*].lote")).containsExactly("L-A", "L-B", null);
        assertThat(JsonPath.<List<Object>>read(c, "$.lineas[2].fechaVencimiento")).isNull();
        assertThat(lotes(herbicida)).containsExactly(Map.entry("L-A", 10.0), Map.entry("L-B", 6.0));
        assertThat(lotes(pala)).isEmpty();

        // Reporte: por defecto 7 días (solo L-A); con 60 días ambos
        String r7 = getJson("/api/v1/reportes/productos-por-vencer");
        assertThat(JsonPath.<List<String>>read(r7, "$.filas[*].lote")).containsExactly("L-A");
        assertThat(JsonPath.<List<String>>read(r7, "$.filas[*].estado")).containsExactly("Por vencer");
        assertThat(JsonPath.<List<String>>read(r7, "$.filas[*].dias_texto")).containsExactly("en 3");
        assertThat(JsonPath.<String>read(r7, "$.subtitulo")).contains("próximos 7 días");
        assertThat(JsonPath.<List<String>>read(getJson("/api/v1/reportes/productos-por-vencer?dias=60"), "$.filas[*].lote"))
                .containsExactly("L-A", "L-B");

        // Venta de 12: FEFO -> 10 de L-A y 2 de L-B
        String f = post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":12}]}""".formatted(finca, herbicida), 201);
        assertThat(lotes(herbicida)).containsExactly(Map.entry("L-B", 4.0));

        // Devolución de 3: vuelve a los lotes de donde salió (los que vencen antes primero)
        post("/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Envase dañado","lineas":[{"facturaLineaId":%d,"cantidad":3}]}"""
                .formatted(id(f), JsonPath.<Integer>read(f, "$.lineas[0].id")), 201);
        assertThat(lotes(herbicida)).containsExactly(Map.entry("L-A", 3.0), Map.entry("L-B", 4.0));

        // Transferencia de 5 a la tienda: salen 3 de L-A y 2 de L-B y llegan con su misma fecha
        long t = id(post("/api/v1/inventario/documentos", """
                {"tipo":"TRANSFERENCIA","almacenId":%d,"almacenDestinoId":%d,"lineas":[{"articuloId":%d,"cantidad":5}]}"""
                .formatted(principal, tienda, herbicida), 201));
        post("/api/v1/inventario/documentos/" + t + "/aplicar", "{}", 200);
        String detalle = getJson("/api/v1/inventario/articulos/" + herbicida + "/lotes");
        assertThat(JsonPath.<List<String>>read(detalle, "$[?(@.almacenId == " + tienda + ")].numero")).containsExactly("L-A", "L-B");
        assertThat(JsonPath.<List<String>>read(detalle, "$[?(@.almacenId == " + tienda + ")].fechaVencimiento"))
                .containsExactly(hoy.plusDays(3).toString(), hoy.plusDays(40).toString());
        assertThat(JsonPath.<List<Number>>read(detalle, "$[?(@.almacenId == " + principal + ")].existencia"))
                .extracting(Number::doubleValue).containsExactly(2.0);

        // Un lote ya vencido en la tienda (simulado): la venta lo salta y el ajuste de salida lo toma primero
        jdbc.update("update lote set fecha_vencimiento = ? where almacen_id = ? and numero = 'L-A'", java.sql.Date.valueOf(hoy.minusDays(2)), tienda);
        String vencidos = getJson("/api/v1/reportes/productos-por-vencer?dias=0&almacenId=" + tienda);
        assertThat(JsonPath.<List<String>>read(vencidos, "$.filas[*].estado")).containsExactly("VENCIDO");
        assertThat(JsonPath.<List<String>>read(vencidos, "$.filas[*].dias_texto")).containsExactly("hace 2");
        long baja = id(post("/api/v1/inventario/documentos", """
                {"tipo":"SALIDA","almacenId":%d,"descripcion":"Baja por vencimiento","lineas":[{"articuloId":%d,"cantidad":3}]}"""
                .formatted(tienda, herbicida), 201));
        post("/api/v1/inventario/documentos/" + baja + "/aplicar", "{}", 200);
        assertThat(JsonPath.<List<Number>>read(getJson("/api/v1/inventario/articulos/" + herbicida + "/lotes"),
                "$[?(@.almacenId == " + tienda + ")].existencia")).extracting(Number::doubleValue).containsExactly(2.0);
        assertThat(JsonPath.<List<String>>read(getJson("/api/v1/inventario/articulos/" + herbicida + "/lotes"),
                "$[?(@.almacenId == " + tienda + ")].numero")).containsExactly("L-B");

        // La suma de los lotes coincide con la existencia del artículo
        Double totalLotes = jdbc.queryForObject("select sum(existencia)::float8 from lote where articulo_id = ?", Double.class, herbicida);
        Double existencia = jdbc.queryForObject("select sum(cantidad)::float8 from existencia where articulo_id = ?", Double.class, herbicida);
        assertThat(totalLotes).isEqualTo(existencia).isEqualTo(4.0);

        // Kárdex imprimible: saldo final = existencia; por almacén; desde mañana todo es saldo anterior y no hay movimientos
        String k = getJson("/api/v1/inventario/articulos/" + herbicida + "/kardex/reporte");
        assertThat(JsonPath.<Number>read(k, "$.saldoFinal").doubleValue()).isEqualTo(4.0);
        assertThat(JsonPath.<Number>read(k, "$.entradas").doubleValue()).isEqualTo(16 + 3 + 5);   // compra + devolución + transferencia
        assertThat(JsonPath.<Number>read(k, "$.salidas").doubleValue()).isEqualTo(12 + 5 + 3);    // venta + transferencia + baja
        List<Number> saldos = JsonPath.read(k, "$.movimientos[*].saldo");
        assertThat(saldos.getLast().doubleValue()).isEqualTo(4.0);
        String kt = getJson("/api/v1/inventario/articulos/" + herbicida + "/kardex/reporte?almacenId=" + tienda);
        assertThat(JsonPath.<String>read(kt, "$.almacen")).isEqualTo("Tienda");
        assertThat(JsonPath.<Number>read(kt, "$.saldoFinal").doubleValue()).isEqualTo(2.0);
        String km = getJson("/api/v1/inventario/articulos/" + herbicida + "/kardex/reporte?desde=" + hoy.plusDays(1));
        assertThat(JsonPath.<Number>read(km, "$.saldoAnterior").doubleValue()).isEqualTo(4.0);
        assertThat(JsonPath.<List<Object>>read(km, "$.movimientos")).isEmpty();
    }

    @Test
    void anularCompraRetiraSusLotes() throws Exception {
        long c = id(post("/api/v1/compras", compra("B0100000009", linea(herbicida, 8, "", hoy.plusDays(90))), 201));
        assertThat(lotes(herbicida)).containsExactly(Map.entry("", 8.0));
        post("/api/v1/compras/" + c + "/anular", "{}", 200);
        assertThat(lotes(herbicida)).isEmpty();
    }

    // ------------------------------------------------------------------ utilidades

    private String compra(String ncf, String lineas) {
        return """
                {"proveedorId":%d,"ncfProveedor":"%s","condicion":"CONTADO","lineas":[%s]}""".formatted(prov, ncf, lineas);
    }

    private static String linea(long articulo, int cantidad, String lote, LocalDate vence) {
        return "{\"articuloId\":%d,\"cantidad\":%d,\"costoUnitario\":300,\"lote\":\"%s\",\"fechaVencimiento\":\"%s\"}"
                .formatted(articulo, cantidad, lote, vence);
    }

    /** Lotes con existencia del almacén principal: número -> existencia, por fecha de vencimiento. */
    private List<Map.Entry<String, Double>> lotes(long articulo) throws Exception {
        String json = getJson("/api/v1/inventario/articulos/" + articulo + "/lotes");
        List<Map<String, Object>> filas = JsonPath.read(json, "$[?(@.almacenId == " + principal + ")]");
        return filas.stream().map(f -> Map.entry((String) f.get("numero"), ((Number) f.get("existencia")).doubleValue())).toList();
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
