package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Catálogo, asientos automáticos de todo el ciclo comercial, reportes y cierre de períodos. */
class ContabilidadIntegrationTest extends IntegrationTestBase {

    String admin, supervisor;
    long urea, finca, proveedor, cuentaBanco;
    final LocalDate hoy = LocalDate.now(ZoneId.of("America/Santo_Domingo"));
    final String rango = "desde=" + hoy.withDayOfMonth(1) + "&hasta=" + hoy;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long sup = usuarioLegacy("super", "superclave", 'S');
        permitir(sup, "CONT_REPORTES");
        admin = login("admin", "adminclave");
        supervisor = login("super", "superclave");
        for (int t : new int[]{31, 34}) {
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
                 "limiteCredito":0,"diasCredito":30}""", 201));
        proveedor = id(post(admin, "/api/v1/catalogos/proveedores", "{\"nombre\":\"Fertilizantes del Cibao\",\"rncCedula\":\"131000002\"}", 201));
        long banco = id(post(admin, "/api/v1/banco/bancos", "{\"nombre\":\"BHD\"}", 201));
        cuentaBanco = id(post(admin, "/api/v1/banco/cuentas", """
                {"bancoId":%d,"numero":"001","nombre":"Operativa","saldoInicial":50000,"fechaSaldoInicial":"2020-01-01"}""".formatted(banco), 201));
    }

    @Test
    void cicloComercialGeneraAsientosBalanceadosYEstadosFinancieros() throws Exception {
        assertThat(num(post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 200), "$.cuentas")).isGreaterThan(30);
        assertThat(code(post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 422))).isEqualTo("CATALOGO_EXISTENTE");

        // Compra a crédito: 10 x 1000 + flete 200 + ITBIS 1800, retención 30% del ITBIS (540)
        long compra = id(post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000001","condicion":"CREDITO","flete":200,"itbisRetenido":540,
                 "lineas":[{"articuloId":%d,"cantidad":10,"costoUnitario":1000}]}""".formatted(proveedor, urea), 201));
        // Venta a crédito: 2 x 2100 + ITBIS 756 = 4956; costo 2 x 1020
        String f = post(admin, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":2}]}""".formatted(finca, urea), 201);
        // Cobro por transferencia con retención de ITBIS
        long recibo = id(post(admin, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":4000,"retencionItbis":226.80}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":4000,"cuentaBancariaId":%d}]}""".formatted(finca, id(f), cuentaBanco), 201));
        // Devolución de 1 saco (reingresa al inventario)
        post(admin, "/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Saco roto","lineas":[{"facturaLineaId":%d,"cantidad":1}]}"""
                .formatted(id(f), JsonPath.<Integer>read(f, "$.lineas[0].id")), 201);
        // Pago al suplidor con cheque
        post(admin, "/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":5000}],
                 "formas":[{"forma":"CHEQUE","monto":5000,"cuentaBancariaId":%d}]}""".formatted(proveedor, compra, cuentaBanco), 201);
        // Gasto menor por caja, comisión bancaria y cheques directos (con y sin contrapartida)
        post(admin, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":1000}", 201);
        post(admin, "/api/v1/caja/sesiones/actual/egresos", "{\"beneficiario\":\"Colmado\",\"concepto\":\"Agua\",\"monto\":500}", 201);
        post(admin, "/api/v1/banco/movimientos", """
                {"cuentaId":%d,"tipo":"NOTA_DEBITO","concepto":"Comisión","monto":150}""".formatted(cuentaBanco), 201);
        post(admin, "/api/v1/banco/movimientos", """
                {"cuentaId":%d,"tipo":"CHEQUE","concepto":"Reparación","monto":300,"cuentaContrapartida":"6101"}""".formatted(cuentaBanco), 201);
        long chequeSinCuenta = id(post(admin, "/api/v1/banco/movimientos", """
                {"cuentaId":%d,"tipo":"CHEQUE","concepto":"Sin cuenta","monto":10}""".formatted(cuentaBanco), 201));

        // Antes de la fecha de inicio contable nada se contabiliza (viene del sistema anterior)
        put(admin, "/api/v1/contabilidad/configuracion/inicio", "{\"fechaInicio\":\"%s\"}".formatted(hoy.plusDays(1)));
        assertThat(num(post(admin, "/api/v1/contabilidad/asientos/generar?simular=true&" + rango, "", 200), "$.asientos")).isEqualTo(0.0);
        put(admin, "/api/v1/contabilidad/configuracion/inicio", "{\"fechaInicio\":\"%s\"}".formatted(hoy.withDayOfMonth(1)));

        // Sin la cuenta de ITBIS por pagar, la factura y la devolución quedan pendientes; lo demás se puede contabilizar
        put(admin, "/api/v1/contabilidad/configuracion", "[{\"clave\":\"ITBIS_POR_PAGAR\",\"codigo\":\"\"}]");
        String sim = post(admin, "/api/v1/contabilidad/asientos/generar?simular=true&" + rango, "", 200);
        assertThat(JsonPath.<List<String>>read(sim, "$.errores[*].origenTipo")).containsExactlyInAnyOrder("FACTURA", "DEVOLUCION", "MOV_BANCO");
        assertThat(JsonPath.<List<String>>read(sim, "$.errores[*].mensaje")).anyMatch(m -> m.contains("ITBIS_POR_PAGAR"))
                .anyMatch(m -> m.contains("contrapartida del cheque"));
        assertThat(num(sim, "$.asientos")).isEqualTo(6.0);
        assertThat(num(getJson(admin, "/api/v1/contabilidad/asientos"), "$.totalElements")).isEqualTo(0.0);   // simulación: no guarda

        put(admin, "/api/v1/contabilidad/configuracion", "[{\"clave\":\"ITBIS_POR_PAGAR\",\"codigo\":\"210201\"}]");
        put(admin, "/api/v1/banco/movimientos/" + chequeSinCuenta + "/contrapartida", "{\"cuentaContrapartida\":\"6101\"}");
        String gen = post(admin, "/api/v1/contabilidad/asientos/generar?" + rango, "", 200);
        assertThat(num(gen, "$.asientos")).isEqualTo(9.0);
        assertThat(JsonPath.<List<?>>read(gen, "$.errores")).isEmpty();
        assertThat(code(putJson(admin, "/api/v1/banco/movimientos/" + chequeSinCuenta + "/contrapartida",
                "{\"cuentaContrapartida\":\"6102\"}", 422))).isEqualTo("MOVIMIENTO_CONTABILIZADO");
        // Idempotente
        assertThat(num(post(admin, "/api/v1/contabilidad/asientos/generar?" + rango, "", 200), "$.asientos")).isEqualTo(0.0);

        // Asiento de la factura
        String asientoFactura = getJson(admin, "/api/v1/contabilidad/asientos?origenTipo=FACTURA");
        assertThat(num(asientoFactura, "$.content[0].total")).isEqualTo(4956 + 2040.0);
        assertThat(JsonPath.<List<String>>read(asientoFactura, "$.content[0].lineas[*].cuentaCodigo"))
                .containsExactly("110201", "4101", "5101", "110301", "210201");

        // Balanza: débitos = créditos
        String balanza = getJson(supervisor, "/api/v1/contabilidad/reportes/balanza?" + rango);
        assertThat(JsonPath.<Boolean>read(balanza, "$.cuadra")).isTrue();
        // Estado de resultados: ventas 4200 - devoluciones 2100; costo 2040 - 1020; gastos 500 + 150 + 300 + 10
        String er = getJson(supervisor, "/api/v1/contabilidad/reportes/estado-resultados?" + rango);
        assertThat(num(er, "$.ingresos.total")).isEqualTo(2100.0);
        assertThat(num(er, "$.costos.total")).isEqualTo(1020.0);
        assertThat(num(er, "$.gastos.total")).isEqualTo(960.0);
        assertThat(num(er, "$.utilidadNeta")).isEqualTo(120.0);
        // Balance general cuadra y el inventario contable coincide con el físico (9 sacos a 1020)
        String bg = getJson(supervisor, "/api/v1/contabilidad/reportes/balance-general?corte=" + hoy);
        assertThat(JsonPath.<Boolean>read(bg, "$.cuadra")).isTrue();
        assertThat(num(bg, "$.resultadoAcumulado")).isEqualTo(120.0);
        long inventario = cuenta("110301");
        assertThat(num(getJson(supervisor, "/api/v1/contabilidad/reportes/mayor/" + inventario + "?" + rango), "$.saldoFinal")).isEqualTo(9180.0);
        // Grupo "Activos corrientes" acumula sus subcuentas
        assertThat(JsonPath.<List<String>>read(balanza, "$.cuentas[*].codigo")).contains("1", "11", "1103");

        // Anular el recibo genera el reverso en la próxima corrida
        post(admin, "/api/v1/cxc/recibos/" + recibo + "/anular", "{\"motivo\":\"Transferencia rechazada\"}", 200);
        String rev = post(admin, "/api/v1/contabilidad/asientos/generar?" + rango, "", 200);
        assertThat(num(rev, "$.reversos")).isEqualTo(1.0);
        String reverso = getJson(admin, "/api/v1/contabilidad/asientos?origenTipo=RECIBO");
        assertThat(JsonPath.<List<Boolean>>read(reverso, "$.content[*].reverso")).containsExactlyInAnyOrder(true, false);
        long automatico = JsonPath.<Number>read(reverso, "$.content[0].id").longValue();
        assertThat(code(post(admin, "/api/v1/contabilidad/asientos/" + automatico + "/anular", "{\"motivo\":\"x\"}", 422)))
                .isEqualTo("ANULAR_DESDE_ORIGEN");
    }

    @Test
    void asientoManualValidaPartidaDobleCuentasYPeriodo() throws Exception {
        post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 200);
        long caja = cuenta("110101"), capital = cuenta("3101"), activos = cuenta("1");
        String manual = """
                {"fecha":"%s","concepto":"Aporte de capital","lineas":[
                 {"cuentaId":%d,"debito":%s},{"cuentaId":%d,"credito":%s}]}""";
        assertThat(code(post(admin, "/api/v1/contabilidad/asientos", manual.formatted(hoy, caja, "100", capital, "90"), 422)))
                .isEqualTo("ASIENTO_DESBALANCEADO");
        assertThat(code(post(admin, "/api/v1/contabilidad/asientos", manual.formatted(hoy, activos, "100", capital, "100"), 422)))
                .isEqualTo("CUENTA_DE_GRUPO");
        String a = post(admin, "/api/v1/contabilidad/asientos", manual.formatted(hoy, caja, "100000", capital, "100000"), 201);
        assertThat(num(a, "$.numero")).isEqualTo(1.0);

        // Una cuenta con movimientos no puede convertirse en grupo
        assertThat(code(post(admin, "/api/v1/contabilidad/cuentas", """
                {"codigo":"11010101","nombre":"Caja chica","tipo":"ACTIVO","padreCodigo":"110101"}""", 422))).isEqualTo("PADRE_CON_MOVIMIENTOS");
        assertThat(code(post(admin, "/api/v1/contabilidad/cuentas", """
                {"codigo":"9","nombre":"x","tipo":"PASIVO","padreCodigo":"1"}""", 422))).isEqualTo("TIPO_DISTINTO_AL_GRUPO");
        // Nueva subcuenta: el padre (de detalle, sin movimientos) pasa a ser de grupo
        post(admin, "/api/v1/contabilidad/cuentas", """
                {"codigo":"120101","nombre":"Vehículos","tipo":"ACTIVO","padreCodigo":"1201"}""", 201);
        assertThat(JsonPath.<List<Boolean>>read(getJson(admin, "/api/v1/contabilidad/cuentas/arbol"),
                "$[?(@.codigo == '1201')].aceptaMovimiento")).containsExactly(false);

        // Período cerrado
        post(admin, "/api/v1/contabilidad/periodos/%d/%d/cerrar".formatted(hoy.getYear(), hoy.getMonthValue()), "", 204);
        assertThat(code(post(admin, "/api/v1/contabilidad/asientos", manual.formatted(hoy, caja, "1", capital, "1"), 422)))
                .isEqualTo("PERIODO_CERRADO");
        assertThat(code(post(admin, "/api/v1/contabilidad/asientos/" + id(a) + "/anular", "{\"motivo\":\"x\"}", 422))).isEqualTo("PERIODO_CERRADO");
        // Solo el administrador cierra/reabre; el supervisor no genera asientos sin la opción
        post(supervisor, "/api/v1/contabilidad/periodos/%d/%d/reabrir".formatted(hoy.getYear(), hoy.getMonthValue()), "", 403);
        post(supervisor, "/api/v1/contabilidad/asientos/generar?" + rango, "", 403);
        post(admin, "/api/v1/contabilidad/periodos/%d/%d/reabrir".formatted(hoy.getYear(), hoy.getMonthValue()), "", 204);
        post(admin, "/api/v1/contabilidad/asientos/" + id(a) + "/anular", "{\"motivo\":\"Error\"}", 200);
        assertThat(JsonPath.<List<?>>read(getJson(supervisor, "/api/v1/contabilidad/reportes/balanza?" + rango), "$.cuentas")).isEmpty();
    }

    @Test
    void cierreAnualLlevaElResultadoACapitalYBloqueaElEjercicio() throws Exception {
        post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 200);
        long caja = cuenta("110101"), ventas = cuenta("4101"), gastos = cuenta("6101");
        int anio = hoy.getYear() - 1;
        String manual = """
                {"fecha":"%s","concepto":"%s","lineas":[{"cuentaId":%d,"debito":%s},{"cuentaId":%d,"credito":%s}]}""";
        post(admin, "/api/v1/contabilidad/asientos", manual.formatted(anio + "-06-15", "Venta", caja, "1000", ventas, "1000"), 201);
        post(admin, "/api/v1/contabilidad/asientos", manual.formatted(anio + "-07-15", "Gasto", gastos, "300", caja, "300"), 201);

        assertThat(code(post(admin, "/api/v1/contabilidad/cierres-anuales/" + hoy.getYear(), "", 422))).isEqualTo("EJERCICIO_EN_CURSO");
        String sim = post(admin, "/api/v1/contabilidad/cierres-anuales/" + anio + "?simular=true", "", 200);
        assertThat(num(sim, "$.resultado")).isEqualTo(700.0);
        assertThat(num(sim, "$.cuentasSaldadas")).isEqualTo(2.0);
        assertThat(JsonPath.<Object>read(sim, "$.asientoId")).isNull();

        String cierre = post(admin, "/api/v1/contabilidad/cierres-anuales/" + anio, "", 200);
        String asiento = getJson(admin, "/api/v1/contabilidad/asientos/" + JsonPath.<Number>read(cierre, "$.asientoId"));
        assertThat(JsonPath.<String>read(asiento, "$.fecha")).isEqualTo(anio + "-12-31");
        assertThat(JsonPath.<List<String>>read(asiento, "$.lineas[*].cuentaCodigo")).containsExactlyInAnyOrder("4101", "6101", "3102");
        assertThat(JsonPath.<List<Number>>read(asiento, "$.lineas[?(@.cuentaCodigo == '3102')].credito")).extracting(Number::doubleValue).containsExactly(700.0);

        // El estado de resultados del año no se ve afectado por el cierre; el balance lleva la utilidad al capital
        String rangoAnio = "desde=%d-01-01&hasta=%d-12-31".formatted(anio, anio);
        assertThat(num(getJson(supervisor, "/api/v1/contabilidad/reportes/estado-resultados?" + rangoAnio), "$.utilidadNeta")).isEqualTo(700.0);
        String bg = getJson(supervisor, "/api/v1/contabilidad/reportes/balance-general?corte=" + anio + "-12-31");
        assertThat(JsonPath.<Boolean>read(bg, "$.cuadra")).isTrue();
        assertThat(num(bg, "$.resultadoAcumulado")).isEqualTo(0.0);
        assertThat(num(bg, "$.capital.total")).isEqualTo(700.0);
        assertThat(JsonPath.<Boolean>read(getJson(supervisor, "/api/v1/contabilidad/reportes/balanza?" + rangoAnio), "$.cuadra")).isTrue();

        // Ejercicio cerrado: no se registra en él ni se cierra dos veces
        assertThat(code(post(admin, "/api/v1/contabilidad/asientos", manual.formatted(anio + "-08-01", "Tarde", caja, "1", ventas, "1"), 422)))
                .isEqualTo("EJERCICIO_CERRADO");
        assertThat(code(post(admin, "/api/v1/contabilidad/cierres-anuales/" + anio, "", 422))).isEqualTo("EJERCICIO_YA_CERRADO");
        post(supervisor, "/api/v1/contabilidad/cierres-anuales/" + anio + "/anular", "{\"motivo\":\"x\"}", 403);

        // Reabrir: anular el cierre, registrar el ajuste y cerrar de nuevo
        post(admin, "/api/v1/contabilidad/cierres-anuales/" + anio + "/anular", "{\"motivo\":\"Ajuste de auditoría\"}", 200);
        post(admin, "/api/v1/contabilidad/asientos", manual.formatted(anio + "-12-20", "Ajuste", gastos, "100", caja, "100"), 201);
        assertThat(num(post(admin, "/api/v1/contabilidad/cierres-anuales/" + anio, "", 200), "$.resultado")).isEqualTo(600.0);
        assertThat(JsonPath.<List<String>>read(getJson(supervisor, "/api/v1/contabilidad/cierres-anuales"), "$[*].estado"))
                .containsExactly("REGISTRADO", "ANULADO");
    }

    @Test
    void sobrantesYFaltantesDeCajaSeContabilizan() throws Exception {
        post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 200);
        // Fondo 1000, se cuentan 1050: sobrante 50
        post(admin, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":1000}", 201);
        String c1 = post(admin, "/api/v1/caja/sesiones/actual/cerrar", "{\"conteo\":[{\"denominacion\":500,\"cantidad\":2},{\"denominacion\":50,\"cantidad\":1}]}", 200);
        assertThat(num(c1, "$.cuadre.diferencia")).isEqualTo(50.0);
        // Fondo 1000, se cuentan 900: faltante 100
        post(admin, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":1000}", 201);
        post(admin, "/api/v1/caja/sesiones/actual/cerrar", "{\"conteo\":[{\"denominacion\":100,\"cantidad\":9}]}", 200);

        String gen = post(admin, "/api/v1/contabilidad/asientos/generar?" + rango, "", 200);
        assertThat(JsonPath.<List<Number>>read(gen, "$.porOrigen[?(@.origenTipo == 'CIERRE_CAJA')].asientos"))
                .extracting(Number::intValue).containsExactly(2);
        String asientos = getJson(admin, "/api/v1/contabilidad/asientos?origenTipo=CIERRE_CAJA");
        assertThat(JsonPath.<List<String>>read(asientos, "$.content[*].lineas[*].cuentaCodigo")).contains("4203", "6103", "110101");
        String er = getJson(supervisor, "/api/v1/contabilidad/reportes/estado-resultados?" + rango);
        assertThat(num(er, "$.ingresos.total")).isEqualTo(50.0);
        assertThat(num(er, "$.gastos.total")).isEqualTo(100.0);
    }

    // ------------------------------------------------------------------ helpers
    private long cuenta(String codigo) throws Exception {
        List<Map<String, Object>> arbol = JsonPath.read(getJson(admin, "/api/v1/contabilidad/cuentas/arbol"), "$");
        return arbol.stream().filter(c -> codigo.equals(c.get("codigo"))).map(c -> ((Number) c.get("id")).longValue()).findFirst().orElseThrow();
    }

    private String post(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString();
    }

    private String putJson(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as("PUT %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString();
    }

    private void put(String token, String url, String body) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as("PUT %s -> %s", url, res.getContentAsString()).isEqualTo(200);
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
