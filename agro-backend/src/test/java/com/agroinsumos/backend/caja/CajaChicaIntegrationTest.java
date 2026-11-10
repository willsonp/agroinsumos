package com.agroinsumos.backend.caja;

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

/** Caja chica: fondo, gastos, reposición, arqueo, 606 y contabilidad. */
class CajaChicaIntegrationTest extends IntegrationTestBase {

    String admin, cajero;
    long cuenta;
    final LocalDate hoy = LocalDate.now(ZoneId.of("America/Santo_Domingo"));

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long caj = usuarioLegacy("cajero", "cajeroclave", 'U');
        permitir(caj, "CAJA_CHICA");
        admin = login("admin", "adminclave");
        cajero = login("cajero", "cajeroclave");
        long banco = id(post(admin, "/api/v1/banco/bancos", "{\"nombre\":\"BHD\"}", 201));
        cuenta = id(post(admin, "/api/v1/banco/cuentas", """
                {"bancoId":%d,"numero":"001","nombre":"Operativa","saldoInicial":100000,"fechaSaldoInicial":"2020-01-01"}""".formatted(banco), 201));
    }

    @Test
    void fondoGastosReposicionArqueo606YContabilidad() throws Exception {
        String fondo = """
                {"nombre":"Caja chica oficina","montoFondo":5000,"apertura":{"forma":"CHEQUE","cuentaBancariaId":%d}}""".formatted(cuenta);
        post(cajero, "/api/v1/caja/chica", fondo, 403);
        long f = id(post(admin, "/api/v1/caja/chica", fondo, 201));
        assertThat(num(getJson(admin, "/api/v1/banco/cuentas/" + cuenta), "$.saldo")).isEqualTo(95000.0);

        String gasto = "/api/v1/caja/chica/" + f + "/gastos";
        long g1 = id(post(cajero, gasto, """
                {"concepto":"Papel y tinta","beneficiario":"Librería Central","rnc":"131000002","ncf":"B0100000123","monto":1180,"itbis":180}""", 201));
        long g2 = id(post(cajero, gasto, "{\"concepto\":\"Pasaje mensajero\",\"monto\":500}", 201));
        assertThat(code(post(cajero, gasto, "{\"concepto\":\"x\",\"monto\":4000}", 422))).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(code(post(cajero, gasto, "{\"concepto\":\"x\",\"ncf\":\"B0100000124\",\"monto\":10}", 422))).isEqualTo("RNC_REQUERIDO");
        assertThat(code(post(cajero, gasto, "{\"concepto\":\"x\",\"rnc\":\"131000002\",\"ncf\":\"b0100000123\",\"monto\":10}", 422)))
                .isEqualTo("NCF_DUPLICADO");
        assertThat(saldo(f)).isEqualTo(3320.0);

        post(cajero, "/api/v1/caja/chica/movimientos/" + g2 + "/anular", "{\"motivo\":\"x\"}", 403);
        post(admin, "/api/v1/caja/chica/movimientos/" + g2 + "/anular", "{\"motivo\":\"Duplicado\"}", 200);
        assertThat(saldo(f)).isEqualTo(3820.0);

        // Reposición con efectivo de la caja del supervisor
        post(admin, "/api/v1/caja/sesiones/abrir", "{\"montoInicial\":2000}", 201);
        String rep = post(admin, "/api/v1/caja/chica/" + f + "/reposiciones", "{\"forma\":\"EFECTIVO\"}", 201);
        assertThat(num(rep, "$.reposicion.monto")).isEqualTo(1180.0);
        assertThat(JsonPath.<List<Integer>>read(rep, "$.gastos[*].id")).containsExactly((int) g1);
        assertThat(num(rep, "$.itbis")).isEqualTo(180.0);
        assertThat(saldo(f)).isEqualTo(5000.0);
        assertThat(num(getJson(admin, "/api/v1/caja/sesiones/actual"), "$.cuadre.efectivoEsperado")).isEqualTo(820.0);
        assertThat(code(post(admin, "/api/v1/caja/chica/" + f + "/reposiciones", "{\"forma\":\"EFECTIVO\"}", 422))).isEqualTo("NADA_QUE_REPONER");
        assertThat(code(post(admin, "/api/v1/caja/chica/movimientos/" + g1 + "/anular", "{\"motivo\":\"x\"}", 422))).isEqualTo("GASTO_REPUESTO");

        String arqueo = post(cajero, "/api/v1/caja/chica/" + f + "/arqueos", "{\"efectivoContado\":4990}", 201);
        assertThat(num(arqueo, "$.diferencia")).isEqualTo(-10.0);

        // 606: el gasto con NCF
        String f606 = getJson(admin, "/api/v1/fiscal/606?periodo=" + hoy.format(DateTimeFormatter.ofPattern("yyyyMM")));
        assertThat(JsonPath.<List<String>>read(f606, "$.lineas[?(@.origen == 'CAJA_CHICA')].ncf")).containsExactly("B0100000123");

        // Contabilidad: apertura, gasto y reposición
        post(admin, "/api/v1/contabilidad/cuentas/plantilla", "", 200);
        String rango = "desde=" + hoy.withDayOfMonth(1) + "&hasta=" + hoy;
        String gen = post(admin, "/api/v1/contabilidad/asientos/generar?" + rango, "", 200);
        assertThat(JsonPath.<List<?>>read(gen, "$.errores")).isEmpty();
        assertThat(JsonPath.<List<Number>>read(gen, "$.porOrigen[?(@.origenTipo == 'CAJA_CHICA')].asientos"))
                .extracting(Number::intValue).containsExactly(3);
        assertThat(JsonPath.<Boolean>read(getJson(admin, "/api/v1/contabilidad/reportes/balanza?" + rango), "$.cuadra")).isTrue();
    }

    private double saldo(long fondo) throws Exception {
        List<Number> s = JsonPath.read(getJson(cajero, "/api/v1/caja/chica"), "$[?(@.id == " + fondo + ")].saldo");
        return s.getFirst().doubleValue();
    }

    private String post(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body)).andReturn().getResponse();
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
