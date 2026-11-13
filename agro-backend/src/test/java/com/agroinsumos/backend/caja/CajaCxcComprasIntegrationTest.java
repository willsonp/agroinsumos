package com.agroinsumos.backend.caja;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Caja (POS, egresos, cierre), Cuentas por Cobrar (recibos, ND e-CF 33) y Compras/CxP. */
class CajaCxcComprasIntegrationTest extends IntegrationTestBase {

    String admin, cajero;
    long urea, finca, consumidor, proveedor;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long cajeroId = usuarioLegacy("cajero", "cajeroclave", 'U');
        permitir(cajeroId, "VENTAS_POS", "CAJA_SESION", "CXC_RECIBOS");
        admin = login("admin", "adminclave");
        cajero = login("cajero", "cajeroclave");
        for (int t : new int[]{31, 32, 33, 34}) {
            post(admin, "/api/v1/ecf/secuencias",
                    "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        post(admin, "/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post(admin, "/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post(admin, "/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Urea 46%%","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,
                 "precioContado":2100,"precioCredito":2100}""".formatted(depto, unidad), 201));
        finca = id(post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CREDITO",
                 "limiteCredito":1000000,"diasCredito":30}""", 201));
        consumidor = id(post(admin, "/api/v1/catalogos/clientes",
                "{\"nombre\":\"Consumidor final\",\"tipoEcfPreferido\":32,\"tipoPrecio\":\"CONTADO\",\"limiteCredito\":0}", 201));
        proveedor = id(post(admin, "/api/v1/catalogos/proveedores", "{\"nombre\":\"Fertilizantes del Cibao\",\"rncCedula\":\"131000002\"}", 201));
    }

    // =================================================================== COMPRAS
    @Test
    void compraConFleteActualizaInventarioCostoPromedioYCxp() throws Exception {
        // 10 sacos a 1000 + 10 sacos a 1300 con flete 600 -> costo final 1330 (1300 + 600/20... solo una línea: 1300+60)
        String c1 = post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000001","condicion":"CREDITO","diasCredito":30,
                 "lineas":[{"articuloId":%d,"cantidad":10,"costoUnitario":1000}]}""".formatted(proveedor, urea), 201);
        assertThat(num(c1, "$.total")).isEqualTo(11800.00);      // 10000 + 18% ITBIS
        assertThat(num(c1, "$.balance")).isEqualTo(11800.00);
        String c2 = post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000002","condicion":"CREDITO","flete":600,"itbisRetenido":702,
                 "lineas":[{"articuloId":%d,"cantidad":10,"costoUnitario":1300}]}""".formatted(proveedor, urea), 201);
        assertThat(num(c2, "$.lineas[0].costoFinal")).isEqualTo(1360.0);   // (13000 + 600) / 10
        assertThat(num(c2, "$.total")).isEqualTo(13000 + 2340 + 600);       // 15940
        assertThat(num(c2, "$.aPagar")).isEqualTo(15940 - 702);
        String art = getJson(admin, "/api/v1/inventario/articulos/" + urea);
        assertThat(num(art, "$.existencia")).isEqualTo(20.0);
        assertThat(num(art, "$.costoPromedio")).isEqualTo(1180.0);          // (10*1000 + 10*1360) / 20

        // NCF duplicado del mismo suplidor
        assertThat(code(post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000001","condicion":"CONTADO",
                 "lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":1}]}""".formatted(proveedor, urea), 409))).isEqualTo("NCF_DUPLICADO");

        // Pago: transferencia (no requiere caja) que debe cuadrar
        assertThat(code(post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":5000}],"formas":[{"forma":"TRANSFERENCIA","monto":4000}]}
                """.formatted(proveedor, id(c1)), 422))).isEqualTo("PAGO_NO_CUADRA");
        post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":5000}],"formas":[{"forma":"TRANSFERENCIA","monto":5000,"referencia":"TRX-1","banco":"BHD"}]}
                """.formatted(proveedor, id(c1)), 201);
        assertThat(num(getJson(admin, "/api/v1/compras/" + id(c1)), "$.balance")).isEqualTo(6800.00);
        // Con pagos no se puede anular
        assertThat(code(post(admin, "/api/v1/compras/" + id(c1) + "/anular", "", 422))).isEqualTo("COMPRA_CON_PAGOS");

        String pend = getJson(admin, "/api/v1/cxp/proveedores/" + proveedor + "/pendientes");
        assertThat(JsonPath.<List<?>>read(pend, "$")).hasSize(2);
        String ant = getJson(admin, "/api/v1/cxp/antiguedad");
        assertThat(num(ant, "$[0].total")).isEqualTo(6800 + 15238.0);
        assertThat(num(ant, "$[0].corriente")).isEqualTo(6800 + 15238.0);

        // Anular la segunda (sin pagos) revierte el inventario
        post(admin, "/api/v1/compras/" + id(c2) + "/anular", "", 200);
        assertThat(num(getJson(admin, "/api/v1/inventario/articulos/" + urea), "$.existencia")).isEqualTo(10.0);
        // El cajero no gestiona compras
        post(cajero, "/api/v1/compras", "{\"proveedorId\":%d,\"condicion\":\"CONTADO\",\"lineas\":[{\"articuloId\":%d,\"cantidad\":1,\"costoUnitario\":1}]}"
                .formatted(proveedor, urea), 403);
    }

    // =================================================================== CAJA / POS
    @Test
    void ventaContadoEnCajaConDevueltaYCierreConConteo() throws Exception {
        comprar(20);
        // Sin caja abierta no se vende, y no se consume e-NCF
        String venta = """
                {"factura":{"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":2}]},
                 "pagos":[%s]}""";
        assertThat(code(post(cajero, "/api/v1/caja/ventas", venta.formatted(consumidor, urea,
                "{\"forma\":\"EFECTIVO\",\"monto\":5000}"), 422))).isEqualTo("CAJA_CERRADA");

        post(cajero, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":1000}", 201);
        post(cajero, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":1000}", 409);

        // Pago insuficiente -> rollback total (ni factura ni e-NCF)
        assertThat(code(post(cajero, "/api/v1/caja/ventas", venta.formatted(consumidor, urea,
                "{\"forma\":\"EFECTIVO\",\"monto\":100}"), 422))).isEqualTo("PAGO_INSUFICIENTE");
        assertThat(jdbc.queryForObject("select count(*) from factura", Integer.class)).isZero();

        // 2 x 2100 = 4200 + 756 = 4956; paga 3000 efectivo + 2000 tarjeta -> devuelta 44
        String r = post(cajero, "/api/v1/caja/ventas", venta.formatted(consumidor, urea,
                "{\"forma\":\"EFECTIVO\",\"monto\":3000},{\"forma\":\"TARJETA\",\"monto\":2000,\"referencia\":\"AUT123\"}"), 201);
        assertThat(JsonPath.<String>read(r, "$.factura.encf")).isEqualTo("E320000000001");
        assertThat(JsonPath.<String>read(r, "$.factura.estado")).isEqualTo("PAGADA");
        assertThat(num(r, "$.recibo.devuelta")).isEqualTo(44.0);
        // Tarjeta no admite devuelta
        assertThat(code(post(cajero, "/api/v1/caja/ventas", venta.formatted(consumidor, urea,
                "{\"forma\":\"TARJETA\",\"monto\":6000}"), 422))).isEqualTo("EXCESO_NO_EFECTIVO");

        // Egreso de caja chica y control de efectivo disponible
        post(cajero, "/api/v1/caja/sesiones/actual/egresos",
                "{\"beneficiario\":\"Colmado\",\"concepto\":\"Agua y café\",\"monto\":500}", 201);
        assertThat(code(post(cajero, "/api/v1/caja/sesiones/actual/egresos",
                "{\"beneficiario\":\"X\",\"concepto\":\"Y\",\"monto\":99999}", 422))).isEqualTo("EFECTIVO_INSUFICIENTE");

        String actual = getJson(cajero, "/api/v1/caja/sesiones/actual");
        // 1000 inicial + (3000 - 44) efectivo - 500 egreso = 3456
        assertThat(num(actual, "$.cuadre.efectivoEsperado")).isEqualTo(3456.0);
        assertThat(JsonPath.<List<Number>>read(actual, "$.cuadre.porForma[?(@.forma=='TARJETA')].neto").getFirst().doubleValue()).isEqualTo(2000.0);

        // Cierre: cuenta 3450 (faltan 6)
        String cierre = post(cajero, "/api/v1/caja/sesiones/actual/cerrar", """
                {"conteo":[{"denominacion":2000,"cantidad":1},{"denominacion":1000,"cantidad":1},{"denominacion":200,"cantidad":2},
                           {"denominacion":50,"cantidad":1}],"observacion":"Cierre turno"}""", 200);
        assertThat(JsonPath.<String>read(cierre, "$.estado")).isEqualTo("CERRADA");
        assertThat(num(cierre, "$.cuadre.efectivoContado")).isEqualTo(3450.0);
        assertThat(num(cierre, "$.cuadre.diferencia")).isEqualTo(-6.0);
        assertThat(code(post(cajero, "/api/v1/caja/sesiones/actual/ingresos", "{\"concepto\":\"x\",\"monto\":1}", 422))).isEqualTo("CAJA_CERRADA");
        // El supervisor ve el arqueo
        String detalle = getJson(admin, "/api/v1/caja/sesiones/" + JsonPath.read(cierre, "$.id"));
        assertThat(JsonPath.<List<?>>read(detalle, "$.movimientos")).hasSize(3);
        // Reporte de cierre: la venta del POS cuenta como factura a contado (1 transacción, efectivo neto + tarjeta)
        String rc = getJson(cajero, "/api/v1/caja/sesiones/" + JsonPath.read(cierre, "$.id") + "/cierre");
        assertThat(JsonPath.<String>read(rc, "$.usuario")).isEqualTo("cajero");
        assertThat(primero(rc, "$.conceptos[?(@.codigo=='MONTO_INICIAL')].monto")).isEqualTo(1000.0);
        assertThat(primero(rc, "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].monto")).isEqualTo(4956.0);
        assertThat(primero(rc, "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].cantidad")).isEqualTo(1.0);
        assertThat(primero(rc, "$.conceptos[?(@.codigo=='RECIBOS_CXC')].cantidad")).isEqualTo(0.0);
        assertThat(primero(rc, "$.conceptos[?(@.codigo=='EGRESO')].monto")).isEqualTo(-500.0);
        assertThat(primero(rc, "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].efectivo")).isEqualTo(2956.0);
        assertThat(primero(rc, "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].tarjeta")).isEqualTo(2000.0);
        assertThat(num(rc, "$.totalDia.monto")).isEqualTo(5456.0);           // 1000 + 4956 - 500 (todas las formas)
        // Contado: 3450 en efectivo + tarjetas no declaradas (se toman las del sistema, 2000) = 5450; faltan 6
        assertThat(num(rc, "$.contado.total")).isEqualTo(5450.0);
        assertThat(num(rc, "$.diferencia")).isEqualTo(-6.0);
        assertThat(JsonPath.<String>read(rc, "$.resultado")).isEqualTo("FALTANTE");
        assertThat(JsonPath.<List<?>>read(rc, "$.detalle.conteo")).hasSize(4);
        assertThat(JsonPath.<List<?>>read(detalle, "$.conteo")).hasSize(4);
    }

    // =================================================================== CxC
    @Test
    void cobroDeCreditoConRetencionTransferenciaYEstadoDeCuenta() throws Exception {
        comprar(20);
        String f = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":5}]}""".formatted(finca, urea), 201);
        long fid = id(f);
        assertThat(num(f, "$.total")).isEqualTo(12390.0);   // 10500 + 1890

        // Abono por transferencia (no requiere caja) con retención de 30% del ITBIS (567)
        String r1 = post(cajero, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":5000,"retencionItbis":567}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":5000,"referencia":"TRX-9"}]}""".formatted(finca, fid), 201);
        assertThat(JsonPath.<Object>read(r1, "$.sesionCajaId")).isNull();
        assertThat(num(getJson(admin, "/api/v1/ventas/facturas/" + fid), "$.balance")).isEqualTo(12390 - 5567.0);

        // Abono mayor al balance
        assertThat(code(post(cajero, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":99999}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":99999}]}""".formatted(finca, fid), 422))).isEqualTo("MONTO_EXCEDE_BALANCE");
        // Efectivo requiere caja abierta
        assertThat(code(post(cajero, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":100}],
                 "pagos":[{"forma":"EFECTIVO","monto":100}]}""".formatted(finca, fid), 422))).isEqualTo("CAJA_CERRADA");

        // Nota de débito por mora (e-CF 33, exenta)
        String nd = post(admin, "/api/v1/cxc/notas-debito",
                "{\"facturaId\":%d,\"concepto\":\"Mora por atraso\",\"monto\":250}".formatted(fid), 201);
        assertThat(JsonPath.<String>read(nd, "$.encf")).isEqualTo("E330000000001");
        assertThat(num(nd, "$.total")).isEqualTo(250.0);

        String pend = getJson(cajero, "/api/v1/cxc/clientes/" + finca + "/pendientes");
        assertThat(JsonPath.<List<?>>read(pend, "$")).hasSize(2);

        // Liquida todo en efectivo con caja abierta
        post(cajero, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);
        String r2 = post(cajero, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":6823},
                                                {"documentoTipo":"NOTA_DEBITO","documentoId":%d,"monto":250}],
                 "pagos":[{"forma":"EFECTIVO","monto":7100}]}""".formatted(finca, fid, id(nd)), 201);
        assertThat(num(r2, "$.devuelta")).isEqualTo(27.0);
        assertThat(JsonPath.<String>read(getJson(admin, "/api/v1/ventas/facturas/" + fid), "$.estado")).isEqualTo("PAGADA");
        assertThat(JsonPath.<String>read(getJson(admin, "/api/v1/cxc/notas-debito/" + id(nd)), "$.estado")).isEqualTo("PAGADA");

        String ec = getJson(admin, "/api/v1/cxc/clientes/" + finca + "/estado-cuenta");
        assertThat(JsonPath.<List<?>>read(ec, "$.movimientos")).hasSize(4);  // factura, ND, 2 recibos
        assertThat(num(ec, "$.saldoFinal")).isEqualTo(0.0);
        assertThat(num(ec, "$.movimientos[1].balance")).isEqualTo(12640.0);  // factura + ND

        // Anular el recibo en efectivo: la caja sigue abierta -> se revierte balance y movimiento
        post(cajero, "/api/v1/cxc/recibos/" + id(r2) + "/anular", "{\"motivo\":\"x\"}", 403);
        post(admin, "/api/v1/cxc/recibos/" + id(r2) + "/anular", "{\"motivo\":\"Cheque devuelto\"}", 200);
        assertThat(num(getJson(admin, "/api/v1/ventas/facturas/" + fid), "$.balance")).isEqualTo(6823.0);
        assertThat(num(getJson(cajero, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(0.0);
        assertThat(num(getJson(admin, "/api/v1/cxc/antiguedad"), "$[0].total")).isEqualTo(6823 + 250.0);
    }

    @Test
    void saldoAFavorDeDevolucionSeUsaComoPago() throws Exception {
        comprar(20);
        post(cajero, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);
        String v = post(cajero, "/api/v1/caja/ventas", """
                {"factura":{"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":2}]},
                 "pagos":[{"forma":"EFECTIVO","monto":4956}]}""".formatted(consumidor, urea), 201);
        // Devuelve 1 saco ya pagado -> 2478 a favor
        post(admin, "/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Sobrante","lineas":[{"facturaLineaId":%d,"cantidad":1}]}"""
                .formatted(JsonPath.<Integer>read(v, "$.factura.id"), JsonPath.<Integer>read(v, "$.factura.lineas[0].id")), 201);
        String f2 = post(cajero, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(consumidor, urea), 201);
        assertThat(code(post(cajero, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":2478}],
                 "pagos":[{"forma":"SALDO_A_FAVOR","monto":3000}]}""".formatted(consumidor, id(f2)), 422))).isEqualTo("EXCESO_NO_EFECTIVO");
        post(cajero, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":2478}],
                 "pagos":[{"forma":"SALDO_A_FAVOR","monto":2478}]}""".formatted(consumidor, id(f2)), 201);
        assertThat(num(getJson(admin, "/api/v1/catalogos/clientes/" + consumidor), "$.saldoAFavor")).isEqualTo(0.0);
        // El saldo a favor no pasa por caja: el efectivo esperado sigue siendo lo cobrado en efectivo
        assertThat(num(getJson(cajero, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(4956.0);
    }

    @Test
    void pagoAProveedorEnEfectivoSaleDeLaCaja() throws Exception {
        String c = comprar(5);
        post(admin, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":2000}", 201);
        assertThat(code(post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":3000}],"formas":[{"forma":"EFECTIVO","monto":3000}]}
                """.formatted(proveedor, id(c)), 422))).isEqualTo("EFECTIVO_INSUFICIENTE");
        String p = post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":1500}],"formas":[{"forma":"EFECTIVO","monto":1500}]}
                """.formatted(proveedor, id(c)), 201);
        assertThat(num(getJson(admin, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(500.0);
        post(admin, "/api/v1/cxp/pagos/" + id(p) + "/anular", "{\"motivo\":\"Error\"}", 200);
        assertThat(num(getJson(admin, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(2000.0);
        assertThat(num(getJson(admin, "/api/v1/compras/" + id(c)), "$.balance")).isEqualTo(5900.0);
    }

    // ------------------------------------------------------------------ helpers
    private String comprar(int cantidad) throws Exception {
        return post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":%d,"costoUnitario":1000}]}"""
                .formatted(proveedor, urea, cantidad), 201);
    }

    private String post(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString();
    }

    private String getJson(String token, String url) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).as("GET %s -> %s", url, res.getContentAsString()).isEqualTo(200);
        return res.getContentAsString();
    }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }

    private static double primero(String json, String path) { return JsonPath.<List<Number>>read(json, path).getFirst().doubleValue(); }

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static String code(String json) { return JsonPath.read(json, "$.code"); }
}
