package com.agroinsumos.backend.venta;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Flujo completo: catálogos -> inventario -> factura con e-CF -> devolución con e-CF 34. */
class VentaIntegrationTest extends IntegrationTestBase {

    String admin, cajero;
    long almacen, almacen2, fertilizante, servicio, clienteCf, consumidor;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long cajeroId = usuarioLegacy("cajero", "cajeroclave", 'U');
        permitir(cajeroId, "VENTAS_POS", "CAJA_SESION", "CXC_RECIBOS");
        admin = login("admin", "adminclave");
        cajero = login("cajero", "cajeroclave");
        for (int t : new int[]{31, 32, 34}) {
            post(admin, "/api/v1/ecf/secuencias",
                    "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        almacen = id(post(admin, "/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201));
        almacen2 = id(post(admin, "/api/v1/catalogos/almacenes", "{\"nombre\":\"Sucursal\",\"principal\":false}", 201));
        long unidad = id(post(admin, "/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\",\"abreviatura\":\"SC\"}", 201));
        long depto = id(post(admin, "/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        fertilizante = id(post(admin, "/api/v1/inventario/articulos", """
                {"codigoBarra":"7460001","nombre":"Fertilizante 15-15-15","tipo":"B","departamentoId":%d,"unidadId":%d,
                 "indicadorItbis":1,"costo":1200,"precioContado":1850,"precioCredito":1950,"precioMayor":1700,"descuentoMaximo":5}
                """.formatted(depto, unidad), 201));
        servicio = id(post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Asesoría técnica","tipo":"S","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":0,"precioContado":1000}
                """.formatted(depto, unidad), 201));
        clienteCf = id(post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"1-01-01010-1","tipoEcfPreferido":31,"tipoPrecio":"CREDITO",
                 "limiteCredito":20000,"diasCredito":30,"exentoItbis":false}
                """, 201));
        consumidor = id(post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Consumidor final","tipoEcfPreferido":32,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":0,"exentoItbis":false}
                """, 201));
        // Entrada inicial de 10 sacos
        long doc = id(post(admin, "/api/v1/inventario/documentos", """
                {"tipo":"ENTRADA","descripcion":"Inventario inicial","lineas":[{"articuloId":%d,"cantidad":10,"costoUnitario":1200}]}
                """.formatted(fertilizante), 201));
        post(admin, "/api/v1/inventario/documentos/" + doc + "/aplicar", "", 200);
    }

    @Test
    void facturaCreditoFiscalACreditoDescargaInventarioYEmiteEcf() throws Exception {
        String f = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[
                  {"articuloId":%d,"cantidad":3,"descuentoPorcentaje":5},
                  {"articuloId":%d,"cantidad":1}]}""".formatted(clienteCf, fertilizante, servicio), 201);
        // Lista CREDITO: 3 x 1950 = 5850 - 5% (292.50) = 5557.50 + servicio 1000 = 6557.50; ITBIS 18% = 1180.35
        assertThat(JsonPath.<Number>read(f, "$.montoGravado").doubleValue()).isEqualTo(6557.50);
        assertThat(JsonPath.<Number>read(f, "$.totalItbis").doubleValue()).isEqualTo(1180.35);
        assertThat(JsonPath.<Number>read(f, "$.total").doubleValue()).isEqualTo(7737.85);
        assertThat(JsonPath.<String>read(f, "$.encf")).isEqualTo("E310000000001");
        assertThat(JsonPath.<Integer>read(f, "$.diasCredito")).isEqualTo(30);
        assertThat(JsonPath.<Number>read(f, "$.balance").doubleValue()).isEqualTo(7737.85);
        assertThat(JsonPath.<Number>read(f, "$.numero").intValue()).isEqualTo(1);

        // El e-CF tiene los mismos totales que la factura
        Integer ecfId = JsonPath.read(f, "$.ecfDocumentoId");
        mvc.perform(get("/api/v1/ecf/documentos/" + ecfId).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.montoTotal").value(7737.85))
                .andExpect(jsonPath("$.origenTipo").value("FACTURA"))
                .andExpect(jsonPath("$.rncComprador").value("101010101"));

        // Existencia: 10 - 3 = 7 ; el servicio no mueve inventario
        assertThat(existencia(fertilizante)).isEqualByComparingTo("7");
        mvc.perform(get("/api/v1/inventario/articulos/" + fertilizante + "/kardex").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.content[0].tipo").value("VENTA"))
                .andExpect(jsonPath("$.content[0].cantidad").value(-3))
                .andExpect(jsonPath("$.content[0].existenciaFinal").value(7));
    }

    @Test
    void cajeroNoPuedeCambiarPrecioNiExcederDescuento() throws Exception {
        post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1,"precioUnitario":100}]}
                """.formatted(consumidor, fertilizante), 403);
        post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1,"descuentoPorcentaje":10}]}
                """.formatted(consumidor, fertilizante), 403);
        // El supervisor/admin sí puede
        post(admin, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1,"descuentoPorcentaje":10}]}
                """.formatted(consumidor, fertilizante), 201);
    }

    @Test
    void sinExistenciaNoSeFacturaNiSeConsumeENcf() throws Exception {
        String err = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":11}]}
                """.formatted(consumidor, fertilizante), 422);
        assertThat(JsonPath.<String>read(err, "$.code")).isEqualTo("EXISTENCIA_INSUFICIENTE");
        assertThat(jdbc.queryForObject("select count(*) from factura", Integer.class)).isZero();
        String ok = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1}]}
                """.formatted(consumidor, fertilizante), 201);
        assertThat(JsonPath.<String>read(ok, "$.encf")).isEqualTo("E320000000001");
        assertThat(JsonPath.<Number>read(ok, "$.numero").intValue()).isEqualTo(1);
    }

    @Test
    void limiteDeCreditoYCreditoSinDiasSonRechazados() throws Exception {
        String err = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":9}]}
                """.formatted(clienteCf, fertilizante), 422);
        assertThat(JsonPath.<String>read(err, "$.code")).isEqualTo("LIMITE_CREDITO_EXCEDIDO");
        err = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":1}]}
                """.formatted(consumidor, fertilizante), 422);
        assertThat(JsonPath.<String>read(err, "$.code")).isEqualTo("SIN_CREDITO");
    }

    @Test
    void devolucionParcialYLuegoAnulacionEmitenNotasDeCredito() throws Exception {
        String f = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":4}]}
                """.formatted(clienteCf, fertilizante), 201);
        long facturaId = id(f);
        Integer lineaId = JsonPath.read(f, "$.lineas[0].id");
        assertThat(existencia(fertilizante)).isEqualByComparingTo("6");

        // Cajero no puede devolver
        post(cajero, "/api/v1/ventas/devoluciones", "{\"facturaId\":%d,\"motivo\":\"x\",\"lineas\":[{\"facturaLineaId\":%d,\"cantidad\":1}]}"
                .formatted(facturaId, lineaId), 403);

        String d = post(admin, "/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Saco roto","lineas":[{"facturaLineaId":%d,"cantidad":1}]}
                """.formatted(facturaId, lineaId), 201);
        // 1 x 1950 = 1950 + 18% = 2301.00
        assertThat(JsonPath.<Number>read(d, "$.total").doubleValue()).isEqualTo(2301.00);
        assertThat(JsonPath.<Integer>read(d, "$.codigoModificacion")).isEqualTo(3);
        assertThat(JsonPath.<String>read(d, "$.encf")).isEqualTo("E340000000001");
        assertThat(existencia(fertilizante)).isEqualByComparingTo("7");

        // El XML de la NC referencia el e-NCF de la factura
        String xml = mvc.perform(get("/api/v1/ecf/documentos/" + JsonPath.read(d, "$.ecfDocumentoId") + "/xml")
                .header("Authorization", "Bearer " + admin)).andReturn().getResponse().getContentAsString();
        assertThat(xml).contains("<NCFModificado>E310000000001</NCFModificado>", "<CodigoModificacion>3</CodigoModificacion>",
                "<MontoTotal>2301.00</MontoTotal>");

        mvc.perform(get("/api/v1/ventas/facturas/" + facturaId).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.balance").value(9204.00 - 2301.00))
                .andExpect(jsonPath("$.lineas[0].cantidadDevuelta").value(1));

        // No se puede devolver más de lo pendiente
        post(admin, "/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"x","lineas":[{"facturaLineaId":%d,"cantidad":4}]}
                """.formatted(facturaId, lineaId), 422);

        // Anulación: devuelve las 3 restantes con código 1
        String a = post(admin, "/api/v1/ventas/facturas/" + facturaId + "/anular", "{\"motivo\":\"Error de facturación\"}", 200);
        assertThat(JsonPath.<Integer>read(a, "$.codigoModificacion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(a, "$.encf")).isEqualTo("E340000000002");
        assertThat(existencia(fertilizante)).isEqualByComparingTo("10");
        mvc.perform(get("/api/v1/ventas/facturas/" + facturaId).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.estado").value("ANULADA"))
                .andExpect(jsonPath("$.balance").value(0.0));
        post(admin, "/api/v1/ventas/facturas/" + facturaId + "/anular", "{\"motivo\":\"otra vez\"}", 422);
    }

    @Test
    void devolucionDeFacturaPagadaGeneraSaldoAFavor() throws Exception {
        String f = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":2}]}
                """.formatted(consumidor, fertilizante), 201);
        long facturaId = id(f);
        jdbc.update("update factura set balance = 0, estado = 'PAGADA' where id = ?", facturaId); // cobrada en caja
        String d = post(admin, "/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Cliente no lo quiso","lineas":[{"facturaLineaId":%d,"cantidad":1,"reingresaInventario":false}]}
                """.formatted(facturaId, JsonPath.<Integer>read(f, "$.lineas[0].id")), 201);
        assertThat(JsonPath.<Number>read(d, "$.saldoAFavor").doubleValue()).isEqualTo(2183.00);
        assertThat(existencia(fertilizante)).isEqualByComparingTo("8"); // no reingresó (dañado)
        mvc.perform(get("/api/v1/catalogos/clientes/" + consumidor).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.saldoAFavor").value(2183.00));
    }

    @Test
    void cotizacionSeFacturaConSusPrecios() throws Exception {
        String c = post(admin, "/api/v1/ventas/cotizaciones", """
                {"clienteId":%d,"lineas":[{"articuloId":%d,"cantidad":2,"precioUnitario":1800,"descuentoPorcentaje":2.5}]}
                """.formatted(consumidor, fertilizante), 201);
        // 2 x 1800 = 3600 - 90 = 3510 + 631.80 = 4141.80
        assertThat(JsonPath.<Number>read(c, "$.total").doubleValue()).isEqualTo(4141.80);
        assertThat(existencia(fertilizante)).isEqualByComparingTo("10");
        // El cajero puede facturarla aunque el precio no sea el de lista (lo autorizó quien cotizó)
        String f = post(cajero, "/api/v1/ventas/cotizaciones/" + id(c) + "/facturar", "{\"condicion\":\"CONTADO\"}", 201);
        assertThat(JsonPath.<Number>read(f, "$.total").doubleValue()).isEqualTo(4141.80);
        assertThat(existencia(fertilizante)).isEqualByComparingTo("8");
        mvc.perform(get("/api/v1/ventas/cotizaciones/" + id(c)).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.estado").value("FACTURADA"));
        post(cajero, "/api/v1/ventas/cotizaciones/" + id(c) + "/facturar", "{\"condicion\":\"CONTADO\"}", 422);
    }

    @Test
    void ajusteYTransferenciaDeInventario() throws Exception {
        long ajuste = id(post(admin, "/api/v1/inventario/documentos", """
                {"tipo":"AJUSTE","descripcion":"Conteo","lineas":[{"articuloId":%d,"cantidad":8}]}""".formatted(fertilizante), 201));
        String ap = post(admin, "/api/v1/inventario/documentos/" + ajuste + "/aplicar", "", 200);
        assertThat(JsonPath.<Number>read(ap, "$.lineas[0].cantidadTeorica").intValue()).isEqualTo(10);
        assertThat(JsonPath.<Number>read(ap, "$.lineas[0].diferencia").intValue()).isEqualTo(-2);
        post(admin, "/api/v1/inventario/documentos/" + ajuste + "/aplicar", "", 422); // ya aplicado

        long tr = id(post(admin, "/api/v1/inventario/documentos", """
                {"tipo":"TRANSFERENCIA","almacenDestinoId":%d,"lineas":[{"articuloId":%d,"cantidad":3}]}""".formatted(almacen2, fertilizante), 201));
        post(admin, "/api/v1/inventario/documentos/" + tr + "/aplicar", "", 200);
        mvc.perform(get("/api/v1/inventario/articulos/" + fertilizante + "/existencias").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.almacenId==%d)].cantidad".formatted(almacen)).value(contains(5.0)))
                .andExpect(jsonPath("$[?(@.almacenId==%d)].cantidad".formatted(almacen2)).value(contains(3.0)));
        // Cajero no gestiona inventario
        post(cajero, "/api/v1/inventario/documentos", "{\"tipo\":\"SALIDA\",\"lineas\":[{\"articuloId\":%d,\"cantidad\":1}]}"
                .formatted(fertilizante), 403);
    }

    @Test
    void validacionesDeCatalogos() throws Exception {
        String e = post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"X","rncCedula":"101010102","tipoEcfPreferido":31,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":0}""", 422);
        assertThat(JsonPath.<String>read(e, "$.code")).isEqualTo("RNC_INVALIDO");
        e = post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"X","tipoEcfPreferido":31,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":0}""", 422);
        assertThat(JsonPath.<String>read(e, "$.code")).isEqualTo("RNC_REQUERIDO");
        e = post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Otra","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CONTADO","limiteCredito":0,"diasCredito":0}""", 409);
        post(cajero, "/api/v1/catalogos/unidades", "{\"nombre\":\"Galón\"}", 403);
        mvc.perform(get("/api/v1/inventario/articulos/codigo/7460001").header("Authorization", "Bearer " + cajero))
                .andExpect(status().isOk()).andExpect(jsonPath("$.existencia").value(10.0));
        mvc.perform(get("/api/v1/inventario/articulos?q=fertil").header("Authorization", "Bearer " + cajero))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void preciosConItbisSeConviertenSinDescuadrarElPrecioFinal() throws Exception {
        long depto = jdbc.queryForObject("select id from departamento limit 1", Long.class);
        long unidad = jdbc.queryForObject("select id from unidad limit 1", Long.class);
        // Igual que el legacy: el usuario digita el precio final (con ITBIS) RD$1,850
        String a = post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Glifosato 1L","tipo":"S","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":0,
                 "precioContado":1850,"preciosIncluyenItbis":true}""".formatted(depto, unidad), 201);
        assertThat(JsonPath.<Number>read(a, "$.precioContado").doubleValue()).isEqualTo(1567.7966);
        assertThat(JsonPath.<Number>read(a, "$.precioContadoConItbis").doubleValue()).isEqualTo(1850.00);
        for (int qty : new int[]{1, 3, 7}) {
            String f = post(cajero, "/api/v1/ventas/facturas", """
                    {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":%d}]}
                    """.formatted(consumidor, id(a), qty), 201);
            assertThat(JsonPath.<Number>read(f, "$.total").doubleValue()).as("total x%d", qty).isEqualTo(1850.00 * qty);
        }
    }

    // ------------------------------------------------------------------ helpers
    private String post(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString();
    }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }

    private BigDecimal existencia(long articulo) {
        return jdbc.queryForObject("select coalesce(sum(cantidad),0) from existencia where articulo_id = ?", BigDecimal.class, articulo);
    }
}
