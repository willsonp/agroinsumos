package com.agroinsumos.backend.venta;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Conduces, apartados y ventas financiadas (módulo 5b del legado). */
class VentasPendientesIntegrationTest extends IntegrationTestBase {

    String admin, cajero;
    long urea, finca, otro;
    final LocalDate hoy = LocalDate.now(ZoneId.of("America/Santo_Domingo"));

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long cajeroId = usuarioLegacy("cajero", "cajeroclave", 'U');
        permitir(cajeroId, "VENTAS_CONDUCES", "VENTAS_APARTADOS", "CAJA_SESION");
        admin = login("admin", "adminclave");
        cajero = login("cajero", "cajeroclave");
        for (int t : new int[]{31, 32, 33}) {
            post(admin, "/api/v1/ecf/secuencias", "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        post(admin, "/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post(admin, "/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post(admin, "/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Urea 46%%","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,
                 "precioContado":2100,"precioCredito":2100}""".formatted(depto, unidad), 201));
        long prov = id(post(admin, "/api/v1/catalogos/proveedores", "{\"nombre\":\"Suplidor\",\"rncCedula\":\"131000002\"}", 201));
        post(admin, "/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":20,"costoUnitario":1000}]}""".formatted(prov, urea), 201);
        finca = id(post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso SRL","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CREDITO","limiteCredito":1000000,"diasCredito":30}""", 201));
        otro = id(post(admin, "/api/v1/catalogos/clientes", """
                {"nombre":"Hacienda Los Pinos SRL","rncCedula":"130000001","tipoEcfPreferido":31,"tipoPrecio":"CREDITO","limiteCredito":1000000,"diasCredito":30}""", 201));
    }

    @Test
    void conducesSalenDelInventarioYSeFacturanJuntos() throws Exception {
        long c1 = id(post(cajero, "/api/v1/ventas/conduces", conduce(finca, 3), 201));
        long c2 = id(post(cajero, "/api/v1/ventas/conduces", conduce(finca, 2), 201));
        assertThat(existencia()).isEqualTo(15.0);
        long c3 = id(post(cajero, "/api/v1/ventas/conduces", conduce(otro, 1), 201));
        assertThat(code(post(cajero, "/api/v1/ventas/conduces/facturar", facturar(c1, c3), 422))).isEqualTo("CONDUCES_DISTINTOS");

        String f = post(cajero, "/api/v1/ventas/conduces/facturar", facturar(c1, c2), 201);
        assertThat(num(f, "$.total")).isEqualTo(12390.0);   // 5 x 2100 + 18%
        assertThat(JsonPath.<String>read(f, "$.encf")).startsWith("E31");
        assertThat(existencia()).isEqualTo(14.0);            // la factura no vuelve a descontar
        String conduce1 = getJson(cajero, "/api/v1/ventas/conduces/" + c1);
        assertThat(JsonPath.<String>read(conduce1, "$.estado")).isEqualTo("FACTURADO");
        assertThat(num(conduce1, "$.facturaId")).isEqualTo((double) id(f));
        assertThat(code(post(cajero, "/api/v1/ventas/conduces/facturar", facturar(c1), 422))).isEqualTo("CONDUCE_NO_ABIERTO");

        // Anular: solo supervisor; la mercancía regresa
        post(cajero, "/api/v1/ventas/conduces/" + c3 + "/anular", "{\"motivo\":\"x\"}", 403);
        post(admin, "/api/v1/ventas/conduces/" + c3 + "/anular", "{\"motivo\":\"El cliente no lo recibió\"}", 200);
        assertThat(existencia()).isEqualTo(15.0);
    }

    @Test
    void conduceDeContadoSeCobraEnCajaAlFacturar() throws Exception {
        long c1 = id(post(cajero, "/api/v1/ventas/conduces", conduce(finca, 1), 201));   // 2100 + 18% = 2478
        String body = """
                {"facturar":{"conduceIds":[%d],"condicion":"CONTADO"},"pagos":[{"forma":"EFECTIVO","monto":%s}]}""";
        // Sin caja abierta, o con pago insuficiente: no se emite la factura y el conduce sigue abierto
        assertThat(code(post(cajero, "/api/v1/ventas/conduces/facturar-contado", body.formatted(c1, "3000"), 422))).isEqualTo("CAJA_CERRADA");
        post(cajero, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);
        assertThat(code(post(cajero, "/api/v1/ventas/conduces/facturar-contado", body.formatted(c1, "1000"), 422))).isEqualTo("PAGO_INSUFICIENTE");
        assertThat(jdbc.queryForObject("select count(*) from factura", Integer.class)).isZero();
        assertThat(JsonPath.<String>read(getJson(cajero, "/api/v1/ventas/conduces/" + c1), "$.estado")).isEqualTo("ABIERTO");

        String r = post(cajero, "/api/v1/ventas/conduces/facturar-contado", body.formatted(c1, "3000"), 201);
        assertThat(JsonPath.<String>read(r, "$.factura.estado")).isEqualTo("PAGADA");
        assertThat(num(r, "$.recibo.devuelta")).isEqualTo(522.0);
        assertThat(existencia()).isEqualTo(19.0);                 // solo salió con el conduce
        assertThat(JsonPath.<String>read(getJson(cajero, "/api/v1/ventas/conduces/" + c1), "$.estado")).isEqualTo("FACTURADO");
        // Entra a la caja como factura a contado
        long sesion = JsonPath.<Number>read(getJson(cajero, "/api/v1/caja/sesiones/actual"), "$.id").longValue();
        String cierre = getJson(cajero, "/api/v1/caja/sesiones/" + sesion + "/cierre");
        assertThat(JsonPath.<java.util.List<Number>>read(cierre, "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].monto").getFirst().doubleValue())
                .isEqualTo(2478.0);
        // A crédito no se usa este cobro
        long c2 = id(post(cajero, "/api/v1/ventas/conduces", conduce(finca, 1), 201));
        assertThat(code(post(cajero, "/api/v1/ventas/conduces/facturar-contado", """
                {"facturar":{"conduceIds":[%d],"condicion":"CREDITO"},"pagos":[{"forma":"EFECTIVO","monto":3000}]}""".formatted(c2), 422)))
                .isEqualTo("CONDICION_INVALIDA");

        // Pago mixto: efectivo + tarjeta + transferencia; la transferencia también cuenta en el cuadre de la caja
        long c3 = id(post(cajero, "/api/v1/ventas/conduces", conduce(finca, 1), 201));
        post(cajero, "/api/v1/ventas/conduces/facturar-contado", """
                {"facturar":{"conduceIds":[%d],"condicion":"CONTADO"},
                 "pagos":[{"forma":"EFECTIVO","monto":1000},{"forma":"TARJETA","monto":500,"referencia":"AUT1"},
                          {"forma":"TRANSFERENCIA","monto":978,"referencia":"TRX-1","banco":"BHD"}]}""".formatted(c3), 201);
        String cierre2 = getJson(cajero, "/api/v1/caja/sesiones/" + sesion + "/cierre");
        String fc = "$.conceptos[?(@.codigo=='FACTURAS_CONTADO')].";
        assertThat(JsonPath.<java.util.List<Number>>read(cierre2, fc + "transferencia").getFirst().doubleValue()).isEqualTo(978.0);
        assertThat(JsonPath.<java.util.List<Number>>read(cierre2, fc + "tarjeta").getFirst().doubleValue()).isEqualTo(500.0);
        assertThat(JsonPath.<java.util.List<Number>>read(cierre2, fc + "efectivo").getFirst().doubleValue()).isEqualTo(2478.0 + 1000.0);
        assertThat(JsonPath.<java.util.List<Number>>read(cierre2, fc + "cantidad").getFirst().intValue()).isEqualTo(2);
        // El efectivo esperado no incluye la tarjeta ni la transferencia
        assertThat(num(getJson(cajero, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(2478.0 + 1000.0);
    }

    @Test
    void apartadoConAbonosSeFacturaConElAnticipo() throws Exception {
        post(cajero, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);
        // 1 saco = 2478 con ITBIS; primer abono de 1000 en efectivo
        String a = post(cajero, "/api/v1/ventas/apartados", """
                {"clienteId":%d,"concepto":"Urea para siembra de diciembre","lineas":[{"articuloId":%d,"cantidad":1}],
                 "abonoInicial":{"forma":"EFECTIVO","monto":1000}}""".formatted(finca, urea), 201);
        long ap = id(a);
        assertThat(num(a, "$.total")).isEqualTo(2478.0);
        assertThat(num(a, "$.pendiente")).isEqualTo(1478.0);
        assertThat(code(post(cajero, "/api/v1/ventas/apartados/" + ap + "/facturar", "{}", 422))).isEqualTo("APARTADO_PENDIENTE");
        assertThat(code(post(cajero, "/api/v1/ventas/apartados/" + ap + "/abonos", "{\"forma\":\"EFECTIVO\",\"monto\":2000}", 422))).isEqualTo("ABONO_EXCEDE");
        post(cajero, "/api/v1/ventas/apartados/" + ap + "/abonos", "{\"forma\":\"TARJETA\",\"monto\":1478}", 200);
        assertThat(num(getJson(admin, "/api/v1/catalogos/clientes/" + finca), "$.saldoAFavor")).isEqualTo(2478.0);
        assertThat(num(getJson(cajero, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(1000.0);

        String f = post(cajero, "/api/v1/ventas/apartados/" + ap + "/facturar", "{}", 201);
        String fac = getJson(admin, "/api/v1/ventas/facturas/" + id(f));
        assertThat(JsonPath.<String>read(fac, "$.estado")).isEqualTo("PAGADA");
        assertThat(num(getJson(admin, "/api/v1/catalogos/clientes/" + finca), "$.saldoAFavor")).isEqualTo(0.0);
        assertThat(existencia()).isEqualTo(19.0);

        // Cancelado con devolución desde la caja (supervisor)
        post(admin, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":1000}", 201);
        long ap2 = id(post(cajero, "/api/v1/ventas/apartados", """
                {"clienteId":%d,"concepto":"Herbicida","lineas":[{"articuloId":%d,"cantidad":1}],
                 "abonoInicial":{"forma":"EFECTIVO","monto":500}}""".formatted(otro, urea), 201));
        post(cajero, "/api/v1/ventas/apartados/" + ap2 + "/cancelar", "{\"motivo\":\"x\",\"devolverDinero\":true}", 403);
        post(admin, "/api/v1/ventas/apartados/" + ap2 + "/cancelar", "{\"motivo\":\"Desistió\",\"devolverDinero\":true}", 200);
        assertThat(num(getJson(admin, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(500.0);
        assertThat(num(getJson(admin, "/api/v1/catalogos/clientes/" + otro), "$.saldoAFavor")).isEqualTo(0.0);

        // Contabilidad: los abonos se contabilizan como anticipo y la balanza cuadra
        post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 200);
        String rango = "desde=" + hoy.withDayOfMonth(1) + "&hasta=" + hoy;
        String gen = post(admin, "/api/v1/contabilidad/asientos/generar?" + rango, "", 200);
        assertThat(JsonPath.<List<?>>read(gen, "$.errores")).isEmpty();
        assertThat(JsonPath.<List<String>>read(gen, "$.porOrigen[*].origenTipo")).contains("ABONO_APARTADO");
        assertThat(JsonPath.<Boolean>read(getJson(admin, "/api/v1/contabilidad/reportes/balanza?" + rango), "$.cuadra")).isTrue();
    }

    @Test
    void loApartadoQuedaReservadoYNoSeVende() throws Exception {
        // 20 sacos en existencia; se apartan 18: solo quedan 2 disponibles para vender
        long a1 = id(post(cajero, "/api/v1/ventas/apartados", """
                {"clienteId":%d,"concepto":"Reserva grande","lineas":[{"articuloId":%d,"cantidad":18}]}""".formatted(finca, urea), 201));
        String art = getJson(admin, "/api/v1/inventario/articulos/" + urea);
        assertThat(num(art, "$.existencia")).isEqualTo(20.0);
        assertThat(num(art, "$.reservado")).isEqualTo(18.0);
        assertThat(num(art, "$.disponible")).isEqualTo(2.0);
        assertThat(num(getJson(admin, "/api/v1/inventario/articulos/" + urea + "/kardex/reporte"), "$.reservado")).isEqualTo(18.0);

        String venta = """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":%d}]}""";
        String err = post(admin, "/api/v1/ventas/facturas", venta.formatted(otro, urea, 3), 422);
        assertThat(code(err)).isEqualTo("EXISTENCIA_RESERVADA");
        assertThat(JsonPath.<String>read(err, "$.detail")).contains("existencia 20, apartado 18, disponible 2");
        assertThat(code(post(cajero, "/api/v1/ventas/conduces", conduce(otro, 3), 422))).isEqualTo("EXISTENCIA_RESERVADA");
        post(admin, "/api/v1/ventas/facturas", venta.formatted(otro, urea, 2), 201);
        assertThat(existencia()).isEqualTo(18.0);
        // Ya no hay disponible ni para otro apartado
        assertThat(code(post(cajero, "/api/v1/ventas/apartados", """
                {"clienteId":%d,"concepto":"Otro","lineas":[{"articuloId":%d,"cantidad":1}]}""".formatted(otro, urea), 422)))
                .isEqualTo("EXISTENCIA_RESERVADA");

        // Cancelar libera la reserva
        post(admin, "/api/v1/ventas/apartados/" + a1 + "/cancelar", "{\"motivo\":\"Desistió\",\"devolverDinero\":false}", 200);
        assertThat(num(getJson(admin, "/api/v1/inventario/articulos/" + urea), "$.disponible")).isEqualTo(18.0);

        // Al facturar el apartado se usa su propia reserva (aunque sea toda la existencia)
        long a2 = id(post(cajero, "/api/v1/ventas/apartados", """
                {"clienteId":%d,"concepto":"Todo","lineas":[{"articuloId":%d,"cantidad":18}],
                 "abonoInicial":{"forma":"TRANSFERENCIA","monto":44604,"referencia":"T-1"}}""".formatted(otro, urea), 201));
        assertThat(code(post(admin, "/api/v1/ventas/facturas", venta.formatted(finca, urea, 1), 422))).isEqualTo("EXISTENCIA_RESERVADA");
        post(cajero, "/api/v1/ventas/apartados/" + a2 + "/facturar", "{}", 201);
        art = getJson(admin, "/api/v1/inventario/articulos/" + urea);
        assertThat(num(art, "$.existencia")).isEqualTo(0.0);
        assertThat(num(art, "$.reservado")).isEqualTo(0.0);
    }

    @Test
    void ventaFinanciadaEnCuotasConInteresYMora() throws Exception {
        String f = post(admin, "/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":2}]}""".formatted(finca, urea), 201);
        long fac = id(f);   // 4956
        // Inicial de 956
        post(admin, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":956}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":956}]}""".formatted(finca, fac), 201);

        String plan = """
                {"facturaId":%d,"tasaInteres":10,"cantidadCuotas":4,"frecuenciaDias":30,"moraPorcentaje":5,"diasGracia":0%s}""";
        assertThat(code(post(admin, "/api/v1/cxc/financiamientos", plan.formatted(fac, ",\"primeraCuota\":\"2000-01-01\""), 422))).isEqualTo("FECHA_INVALIDA");
        String p = post(admin, "/api/v1/cxc/financiamientos", plan.formatted(fac, ""), 201);
        long pid = id(p);
        assertThat(num(p, "$.capital")).isEqualTo(4000.0);
        assertThat(num(p, "$.interes")).isEqualTo(400.0);
        assertThat(JsonPath.<List<Number>>read(p, "$.cuotas[*].monto")).extracting(Number::doubleValue).containsExactly(1100.0, 1100.0, 1100.0, 1100.0);
        assertThat(JsonPath.<String>read(p, "$.cuotas[0].vencimiento")).isEqualTo(hoy.plusMonths(1).toString());
        long nd = JsonPath.<Number>read(p, "$.notaDebitoId").longValue();
        assertThat(JsonPath.<String>read(getJson(admin, "/api/v1/cxc/notas-debito/" + nd), "$.encf")).startsWith("E33");
        assertThat(code(post(admin, "/api/v1/cxc/financiamientos", plan.formatted(fac, ""), 422))).isEqualTo("FACTURA_YA_FINANCIADA");

        // Cobro de 1500: cuota 1 saldada y 400 a la cuota 2
        post(admin, "/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":1500}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":1500}]}""".formatted(finca, fac), 201);
        String e = getJson(admin, "/api/v1/cxc/financiamientos/" + pid);
        assertThat(JsonPath.<List<Number>>read(e, "$.cuotas[*].pendiente")).extracting(Number::doubleValue).containsExactly(0.0, 700.0, 1100.0, 1100.0);
        assertThat(num(e, "$.pendiente")).isEqualTo(2900.0);
        assertThat(code(post(admin, "/api/v1/cxc/financiamientos/" + pid + "/mora", "", 422))).isEqualTo("SIN_MORA");

        // La cuota 2 vence hace 10 días: aparece en vencidas con mora del 5% de lo pendiente
        jdbc.update("update plan_cuota set vencimiento = ? where plan_id = ? and numero <= 2", java.sql.Date.valueOf(hoy.minusDays(10)), pid);
        String v = getJson(admin, "/api/v1/cxc/financiamientos/vencidas");
        assertThat(JsonPath.<List<Integer>>read(v, "$[*].cuota")).containsExactly(2);
        assertThat(num(v, "$[0].moraPorCobrar")).isEqualTo(35.0);
        assertThat(num(v, "$[0].diasAtraso")).isEqualTo(10.0);
        String m = post(admin, "/api/v1/cxc/financiamientos/" + pid + "/mora", "", 200);
        assertThat(num(m, "$.moraPorCobrar")).isEqualTo(0.0);
        assertThat(JsonPath.<List<Boolean>>read(m, "$.cuotas[*].moraCobrada")).containsExactly(false, true, false, false);
        assertThat(code(post(admin, "/api/v1/cxc/financiamientos/" + pid + "/mora", "", 422))).isEqualTo("SIN_MORA");
        // La nota de mora queda pendiente de cobro en la CxC del cliente
        String pend = getJson(admin, "/api/v1/cxc/clientes/" + finca + "/pendientes");
        assertThat(pend).contains("35");
    }

    // ------------------------------------------------------------------ helpers
    private String conduce(long cliente, int cantidad) {
        return """
                {"clienteId":%d,"lugarEntrega":"Finca, km 5","lineas":[{"articuloId":%d,"cantidad":%d}]}""".formatted(cliente, urea, cantidad);
    }

    private String facturar(long... ids) {
        return "{\"conduceIds\":%s,\"condicion\":\"CREDITO\"}".formatted(java.util.Arrays.toString(ids));
    }

    private double existencia() throws Exception { return num(getJson(admin, "/api/v1/inventario/articulos/" + urea), "$.existencia"); }

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

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static String code(String json) { return JsonPath.read(json, "$.code"); }
}
