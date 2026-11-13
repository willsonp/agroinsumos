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

/** Banco: cuentas, cheques, transferencias, depósitos desde caja, pagos/recibos vinculados y conciliación. */
class BancoIntegrationTest extends IntegrationTestBase {

    String admin, supervisor, cajero;
    long bhd, popular, cuentaBhd, cuentaPopular, proveedor;
    final String hoy = LocalDate.now(ZoneId.of("America/Santo_Domingo")).toString();

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long supId = usuarioLegacy("super", "superclave", 'S');
        long cajeroId = usuarioLegacy("cajero", "cajeroclave", 'U');
        permitir(supId, "BANCO_MOVIMIENTOS", "CAJA_SESION");
        permitir(cajeroId, "CAJA_SESION", "BANCO_MOVIMIENTOS");
        admin = login("admin", "adminclave");
        supervisor = login("super", "superclave");
        cajero = login("cajero", "cajeroclave");
        bhd = id(post(admin, "/api/v1/banco/bancos", "{\"nombre\":\"Banco BHD\"}", 201));
        popular = id(post(admin, "/api/v1/banco/bancos", "{\"nombre\":\"Banco Popular\"}", 201));
        cuentaBhd = id(post(admin, "/api/v1/banco/cuentas", """
                {"bancoId":%d,"numero":"001-123","nombre":"BHD Operativa","saldoInicial":50000,
                 "fechaSaldoInicial":"2020-01-01","proximoCheque":1001}""".formatted(bhd), 201));
        cuentaPopular = id(post(admin, "/api/v1/banco/cuentas", """
                {"bancoId":%d,"numero":"777-1","nombre":"Popular Nómina","saldoInicial":0,"fechaSaldoInicial":"2020-01-01"}"""
                .formatted(popular), 201));
        proveedor = id(post(admin, "/api/v1/catalogos/proveedores", "{\"nombre\":\"Agroquímicos SRL\",\"rncCedula\":\"131000002\"}", 201));
    }

    @Test
    void chequesNumeradosSinDuplicadoYSinSobregiro() throws Exception {
        String c1 = post(admin, "/api/v1/banco/movimientos", cheque(null, 1500), 201);
        assertThat(JsonPath.<String>read(c1, "$.referencia")).isEqualTo("1001");
        assertThat(num(c1, "$.montoConSigno")).isEqualTo(-1500.0);
        String c2 = post(admin, "/api/v1/banco/movimientos", cheque(null, 500), 201);
        assertThat(JsonPath.<String>read(c2, "$.referencia")).isEqualTo("1002");
        assertThat(code(post(admin, "/api/v1/banco/movimientos", cheque("1001", 10), 409))).isEqualTo("CHEQUE_DUPLICADO");
        // Un número manual mayor adelanta la secuencia
        post(admin, "/api/v1/banco/movimientos", cheque("1050", 10), 201);
        assertThat(JsonPath.<String>read(post(admin, "/api/v1/banco/movimientos", cheque(null, 10), 201), "$.referencia")).isEqualTo("1051");
        assertThat(code(post(admin, "/api/v1/banco/movimientos", cheque(null, 999999), 422))).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(50000 - 1500 - 500 - 20.0);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.proximoCheque")).isEqualTo(1052.0);
        // Anular cheque restituye el saldo; transferencias no se registran por el endpoint manual
        post(admin, "/api/v1/banco/movimientos/" + id(c1) + "/anular", "{\"motivo\":\"Dañado\"}", 200);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(50000 - 520.0);
        assertThat(code(post(admin, "/api/v1/banco/movimientos", """
                {"cuentaId":%d,"tipo":"TRANSFERENCIA_SALIDA","concepto":"x","monto":1}""".formatted(cuentaBhd), 422))).isEqualTo("TIPO_INVALIDO");
    }

    @Test
    void transferenciaEntreCuentasYAnulacionDelPar() throws Exception {
        String t = post(admin, "/api/v1/banco/transferencias", """
                {"cuentaOrigenId":%d,"cuentaDestinoId":%d,"monto":20000,"concepto":"Fondo de nómina"}"""
                .formatted(cuentaBhd, cuentaPopular), 201);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(30000.0);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaPopular), "$.saldo")).isEqualTo(20000.0);
        assertThat(code(post(admin, "/api/v1/banco/transferencias", """
                {"cuentaOrigenId":%d,"cuentaDestinoId":%d,"monto":20001,"concepto":"x"}"""
                .formatted(cuentaPopular, cuentaBhd), 422))).isEqualTo("SALDO_INSUFICIENTE");
        post(admin, "/api/v1/banco/movimientos/" + id(t) + "/anular", "{\"motivo\":\"Error\"}", 200);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(50000.0);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaPopular), "$.saldo")).isEqualTo(0.0);
    }

    @Test
    void depositoDesdeCajaSaleDelEfectivoYSuAnulacionLoDevuelve() throws Exception {
        // El cajero (rol USUARIO) no puede registrar movimientos aunque tenga la opción
        assertThat(status(cajero, "/api/v1/banco/movimientos", deposito(1000, true))).isEqualTo(403);
        post(supervisor, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":5000}", 201);
        String d = post(supervisor, "/api/v1/banco/movimientos", deposito(3000, true), 201);
        assertThat(num(getJson(supervisor, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(2000.0);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(53000.0);
        post(supervisor, "/api/v1/banco/movimientos/" + id(d) + "/anular", "{\"motivo\":\"Error\"}", 200);
        assertThat(num(getJson(supervisor, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(5000.0);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(50000.0);
    }

    @Test
    void pagoASuplidorConChequeGeneraMovimientoYSeAnulaDesdeElPago() throws Exception {
        long compra = id(post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CREDITO","lineas":[]}""".formatted(proveedor), 201, true));
        String pago = post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":1180}],
                 "formas":[{"forma":"CHEQUE","monto":1180,"cuentaBancariaId":%d}]}""".formatted(proveedor, compra, cuentaBhd), 201);
        assertThat(JsonPath.<String>read(pago, "$.formas[0].referencia")).isEqualTo("1001");
        String movs = getJson(admin, "/api/v1/banco/movimientos?cuentaId=" + cuentaBhd);
        assertThat(JsonPath.<String>read(movs, "$.content[0].origenTipo")).isEqualTo("PAGO_PROVEEDOR");
        assertThat(JsonPath.<String>read(movs, "$.content[0].beneficiario")).isEqualTo("AGROQUÍMICOS SRL");
        long mov = JsonPath.<Number>read(movs, "$.content[0].id").longValue();
        // No se anula desde banco, sino anulando el pago
        assertThat(code(post(admin, "/api/v1/banco/movimientos/" + mov + "/anular", "{\"motivo\":\"x\"}", 422))).isEqualTo("ANULAR_DESDE_ORIGEN");
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(48820.0);
        post(admin, "/api/v1/cxp/pagos/" + id(pago) + "/anular", "{\"motivo\":\"Cheque extraviado\"}", 200);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd), "$.saldo")).isEqualTo(50000.0);
        // La cuenta solo aplica a cheques y transferencias
        assertThat(code(post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":10}],
                 "formas":[{"forma":"EFECTIVO","monto":10,"cuentaBancariaId":%d}]}""".formatted(proveedor, compra, cuentaBhd), 422)))
                .isEqualTo("CUENTA_NO_APLICA");
    }

    @Test
    void conciliacionCuadraYBloqueaElPeriodo() throws Exception {
        long dep = id(post(admin, "/api/v1/banco/movimientos", deposito(10000, false), 201));
        long chq = id(post(admin, "/api/v1/banco/movimientos", cheque(null, 4000), 201));
        long nd = id(post(admin, "/api/v1/banco/movimientos", """
                {"cuentaId":%d,"tipo":"NOTA_DEBITO","concepto":"Comisión","monto":250}""".formatted(cuentaBhd), 201));
        // Libros: 50000 + 10000 - 4000 - 250 = 55750. El banco solo registró el depósito y la comisión: 59750
        String body = """
                {"cuentaId":%d,"fechaCorte":"%s","saldoBanco":%s,"movimientoIds":[%d,%d]}""";
        String prev = post(admin, "/api/v1/banco/conciliaciones/calcular", body.formatted(cuentaBhd, hoy, "59750", dep, nd), 200);
        assertThat(num(prev, "$.saldoLibros")).isEqualTo(55750.0);
        assertThat(num(prev, "$.chequesTransito")).isEqualTo(4000.0);
        assertThat(JsonPath.<Boolean>read(prev, "$.cuadra")).isTrue();
        assertThat(code(post(admin, "/api/v1/banco/conciliaciones", body.formatted(cuentaBhd, hoy, "59700", dep, nd), 422)))
                .isEqualTo("CONCILIACION_NO_CUADRA");
        // El supervisor sin la opción de conciliación no accede
        assertThat(status(supervisor, "/api/v1/banco/conciliaciones", body.formatted(cuentaBhd, hoy, "59750", dep, nd))).isEqualTo(403);
        post(admin, "/api/v1/banco/conciliaciones", body.formatted(cuentaBhd, hoy, "59750", dep, nd), 201);
        assertThat(code(post(admin, "/api/v1/banco/movimientos", deposito(1, false), 422))).isEqualTo("PERIODO_CONCILIADO");
        assertThat(code(post(admin, "/api/v1/banco/movimientos/" + dep + "/anular", "{\"motivo\":\"x\"}", 422))).isEqualTo("MOVIMIENTO_CONCILIADO");
        // El cheque en tránsito sigue pendiente para la próxima conciliación
        assertThat(code(post(admin, "/api/v1/banco/conciliaciones/calcular", body.formatted(cuentaBhd, hoy, "0", chq, chq), 422)))
                .isEqualTo("FECHA_CORTE_INVALIDA");
        assertThat(JsonPath.<List<?>>read(getJson(admin, "/api/v1/banco/cuentas/" + cuentaBhd + "/conciliaciones"), "$")).hasSize(1);
    }

    @Test
    void reciboPorTransferenciaAcreditaLaCuentaYSeReversaAlAnular() throws Exception {
        post(admin, "/api/v1/ecf/secuencias", "{\"tipoEcf\":31,\"secuenciaDesde\":1,\"secuenciaHasta\":50,\"fechaVencimiento\":\"2030-12-31\"}", 201);
        post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CREDITO","lineas":[]}""".formatted(proveedor), 201, true);
        long art = JsonPath.<Number>read(getJson(admin, "/api/v1/inventario/articulos"), "$.content[0].id").longValue();
        long cliente = id(post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Finca La Esperanza SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CREDITO",
                 "limiteCredito":1000000,"diasCredito":30}""", 201));
        long fac = id(post(admin, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(cliente, art), 201));
        String rec = post(admin, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":1000}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":1000,"referencia":"TRX-55","cuentaBancariaId":%d}]}"""
                .formatted(cliente, fac, cuentaPopular), 201);
        assertThat(num(rec, "$.pagos[0].cuentaBancariaId")).isEqualTo((double) cuentaPopular);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaPopular), "$.saldo")).isEqualTo(1000.0);
        String movs = getJson(admin, "/api/v1/banco/movimientos?cuentaId=" + cuentaPopular);
        assertThat(JsonPath.<String>read(movs, "$.content[0].tipo")).isEqualTo("TRANSFERENCIA_ENTRADA");
        assertThat(JsonPath.<String>read(movs, "$.content[0].referencia")).isEqualTo("TRX-55");
        // Los cheques recibidos pasan por caja y luego se depositan: no se acreditan directo
        assertThat(code(post(admin, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":10}],
                 "pagos":[{"forma":"CHEQUE","monto":10,"cuentaBancariaId":%d}]}""".formatted(cliente, fac, cuentaPopular), 422)))
                .isEqualTo("CUENTA_NO_APLICA");
        post(admin, "/api/v1/cxc/recibos/" + id(rec) + "/anular", "{\"motivo\":\"Transferencia rechazada\"}", 200);
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuentaPopular), "$.saldo")).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------ helpers
    private String cheque(String numero, double monto) {
        return """
                {"cuentaId":%d,"tipo":"CHEQUE","referencia":%s,"beneficiario":"Juan Pérez","concepto":"Pago servicio","monto":%s}"""
                .formatted(cuentaBhd, numero == null ? "null" : "\"" + numero + "\"", monto);
    }

    private String deposito(double monto, boolean desdeCaja) {
        return """
                {"cuentaId":%d,"tipo":"DEPOSITO","concepto":"Depósito","monto":%s,"desdeCaja":%s}""".formatted(cuentaBhd, monto, desdeCaja);
    }

    private String post(String token, String url, String body, int esperado) throws Exception {
        return post(token, url, body, esperado, false);
    }

    /** Con {@code compraServicio} reemplaza las líneas vacías por un servicio gravado de 1000 (+ITBIS). */
    private String post(String token, String url, String body, int esperado, boolean compraServicio) throws Exception {
        if (compraServicio) {
            long unidad = id(post(admin, "/api/v1/catalogos/unidades", "{\"nombre\":\"Und\"}", 201));
            long depto = id(post(admin, "/api/v1/catalogos/departamentos", "{\"nombre\":\"Insumos\",\"tipo\":\"I\"}", 201));
            post(admin, "/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
            long art = id(post(admin, "/api/v1/inventario/articulos", """
                    {"nombre":"Herbicida","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,
                     "precioContado":2000,"precioCredito":2000}""".formatted(depto, unidad), 201));
            body = body.replace("\"lineas\":[]", "\"lineas\":[{\"articuloId\":%d,\"cantidad\":1,\"costoUnitario\":1000}]".formatted(art));
        }
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString();
    }

    private int status(String token, String url, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andReturn().getResponse().getStatus();
    }

    private String getJson(String token, String url) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).as("GET %s -> %s", url, res.getContentAsString()).isEqualTo(200);
        return res.getContentAsString();
    }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static String code(String json) { return JsonPath.read(json, "$.code"); }
}
