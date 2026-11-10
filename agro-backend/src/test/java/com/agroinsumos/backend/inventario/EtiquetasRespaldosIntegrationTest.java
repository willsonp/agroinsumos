package com.agroinsumos.backend.inventario;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Etiquetas ZPL (vista previa, archivo, impresora de red) y respaldos de la base de datos. */
class EtiquetasRespaldosIntegrationTest extends IntegrationTestBase {

    String admin, bodeguero, supervisor;
    @org.springframework.beans.factory.annotation.Autowired com.agroinsumos.backend.admin.respaldo.RespaldoService respaldos;
    long urea, herbicida;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        long bod = usuarioLegacy("bodega", "bodegaclave", 'U');
        long sup = usuarioLegacy("super", "superclave", 'S');
        permitir(bod, "INV_ETIQUETAS");
        permitir(sup, "ADMIN_RESPALDOS");
        admin = login("admin", "adminclave");
        bodeguero = login("bodega", "bodegaclave");
        supervisor = login("super", "superclave");
        long unidad = id(post(admin, "/api/v1/catalogos/unidades", "{\"nombre\":\"Saco\"}", 201));
        long depto = id(post(admin, "/api/v1/catalogos/departamentos", "{\"nombre\":\"Fertilizantes\",\"tipo\":\"I\"}", 201));
        urea = id(post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Urea 46%% saco 50 kg","codigoBarra":"7461234000014","tipo":"B","departamentoId":%d,"unidadId":%d,
                 "indicadorItbis":1,"costo":1000,"precioContado":2100,"precioCredito":2100}""".formatted(depto, unidad), 201));
        herbicida = id(post(admin, "/api/v1/inventario/articulos", """
                {"nombre":"Herbicida glifosato 1 L","referencia":"HERB-01","tipo":"B","departamentoId":%d,"unidadId":%d,
                 "indicadorItbis":1,"costo":300,"precioContado":500,"precioCredito":500}""".formatted(depto, unidad), 201));
    }

    @Test
    void vistaPreviaArchivoEImpresoraDeRed() throws Exception {
        String req = """
                {"anchoMm":50.8,"altoMm":25.4,"dpi":%d,"precio":"CONTADO","mostrarCodigo":true,
                 "items":[{"articuloId":%d,"cantidad":3},{"articuloId":%d,"cantidad":2}]}""";
        String r = post(bodeguero, "/api/v1/inventario/etiquetas/vista-previa", req.formatted(203, urea, herbicida), 200);
        assertThat(num(r, "$.totalEtiquetas")).isEqualTo(5.0);
        assertThat(JsonPath.<String>read(r, "$.etiquetas[0].precio")).isEqualTo("RD$2,478.00");
        assertThat(JsonPath.<String>read(r, "$.etiquetas[0].simbologia")).isEqualTo("EAN13");
        assertThat(JsonPath.<String>read(r, "$.etiquetas[1].codigo")).isEqualTo("HERB-01");
        assertThat(JsonPath.<String>read(r, "$.etiquetas[1].simbologia")).isEqualTo("CODE128");
        assertThat(num(r, "$.etiquetas[0].diseno.anchoPuntos")).isEqualTo(406.0);
        String zpl = JsonPath.read(r, "$.zpl");
        assertThat(zpl.split("\\^XA", -1)).hasSize(3);   // una plantilla por artículo
        assertThat(zpl).contains("^PQ3,").contains("^PQ2,").contains("^FDRD$2,478.00^FS");
        assertThat(num(post(bodeguero, "/api/v1/inventario/etiquetas/vista-previa", req.formatted(300, urea, herbicida), 200),
                "$.etiquetas[0].diseno.anchoPuntos")).isEqualTo(610.0);
        assertThat(code(post(bodeguero, "/api/v1/inventario/etiquetas/vista-previa", req.formatted(150, urea, herbicida), 422))).isEqualTo("DPI_INVALIDO");

        var archivo = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/inventario/etiquetas/zpl")
                .header("Authorization", "Bearer " + bodeguero).contentType("application/json").content(req.formatted(203, urea, herbicida)))
                .andReturn().getResponse();
        assertThat(archivo.getHeader("Content-Disposition")).contains("etiquetas.zpl");
        assertThat(archivo.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(zpl);

        // Impresora de red simulada en el puerto RAW
        try (var servidor = new ServerSocket(9100, 1, InetAddress.getLoopbackAddress())) {
            var recibido = CompletableFuture.supplyAsync(() -> {
                try (var s = servidor.accept(); var out = new ByteArrayOutputStream()) {
                    s.getInputStream().transferTo(out);
                    return out.toString(StandardCharsets.UTF_8);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            long imp = id(post(bodeguero, "/api/v1/inventario/impresoras", """
                    {"nombre":"Zebra bodega","host":"127.0.0.1","puerto":9100,"dpi":203,"anchoMm":50.8,"altoMm":25.4}""", 201));
            String res = post(bodeguero, "/api/v1/inventario/etiquetas/imprimir/" + imp, req.formatted(203, urea, herbicida), 200);
            assertThat(num(res, "$.totalEtiquetas")).isEqualTo(5.0);
            assertThat(recibido.get(5, TimeUnit.SECONDS)).isEqualTo(zpl);
        }
        // Solo puertos de impresora
        assertThat(code(post(bodeguero, "/api/v1/inventario/impresoras", """
                {"nombre":"Otra","host":"10.0.0.5","puerto":5432,"dpi":203,"anchoMm":50.8,"altoMm":25.4}""", 400))).isEqualTo("VALIDACION");
    }

    @Test
    void encabezadoConDatosDeLaEmpresaYSinPrecio() throws Exception {
        var put = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/empresa")
                .header("Authorization", "Bearer " + admin).contentType("application/json").content("""
                {"nombre":"AGROINSUMOS SRL","nombreComercial":"PV Agroinsumos","direccion":"Av. Duarte 1, Santiago","telefono":"809-555-0101"}"""))
                .andReturn().getResponse();
        assertThat(put.getStatus()).isEqualTo(200);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/empresa")
                .header("Authorization", "Bearer " + bodeguero).contentType("application/json").content("{\"nombre\":\"X\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        String r = post(bodeguero, "/api/v1/inventario/etiquetas/vista-previa", """
                {"anchoMm":57.2,"altoMm":31.8,"dpi":203,"mostrarEmpresa":true,"mostrarDireccion":true,"mostrarTelefono":true,
                 "items":[{"articuloId":%d,"cantidad":1}]}""".formatted(urea), 200);
        String zpl = JsonPath.read(r, "$.zpl");
        assertThat(zpl).contains("^FDPV Agroinsumos^FS").contains("Tel. 809-555-0101").doesNotContain("RD$");
        assertThat(JsonPath.<Object>read(r, "$.etiquetas[0].precio")).isNull();
        List<String> textos = JsonPath.read(r, "$.etiquetas[0].diseno.elementos[?(@.tipo == 'TEXTO')].texto");
        assertThat(textos.getFirst()).isEqualTo("PV Agroinsumos");
        // Selección de artículos para la pestaña: por texto y solo con existencia
        String sel = mvc.perform(get("/api/v1/inventario/articulos/seleccion?q=herb").header("Authorization", "Bearer " + bodeguero))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(sel, "$.content[*].nombre")).containsExactly("Herbicida glifosato 1 L");
        String conExistencia = mvc.perform(get("/api/v1/inventario/articulos/seleccion?conExistencia=true").header("Authorization", "Bearer " + bodeguero))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(conExistencia, "$.totalElements")).isZero();
        // Con todo y precio no cabe en 1.5 x 1: se informa
        assertThat(code(post(bodeguero, "/api/v1/inventario/etiquetas/vista-previa", """
                {"anchoMm":38.1,"altoMm":15,"dpi":203,"precio":"CONTADO","mostrarEmpresa":true,"mostrarDireccion":true,
                 "items":[{"articuloId":%d,"cantidad":1}]}""".formatted(urea), 422))).isEqualTo("ETIQUETA_PEQUENA");
    }

    @Test
    void respaldoCompletoConNombreDelDia(@TempDir Path carpeta) throws Exception {
        post(supervisor, "/api/v1/admin/respaldos", "", 403);   // solo administrador
        put("/api/v1/admin/respaldos/directorio", "{\"directorio\":\"%s\"}".formatted(carpeta.toString().replace("\\", "\\\\")));
        assertThat(code(putCodigo("/api/v1/admin/respaldos/directorio", "{\"directorio\":\"relativa/carpeta\"}"))).isEqualTo("DIRECTORIO_INVALIDO");

        String dia = String.format("%02d", LocalDate.now(java.time.ZoneId.of("America/Santo_Domingo")).getDayOfMonth());
        assertThat(JsonPath.<Boolean>read(getJson("/api/v1/admin/respaldos"), "$.respaldoDeHoy")).isFalse();
        String r = post(admin, "/api/v1/admin/respaldos", "", 200);
        String nombre = JsonPath.read(r, "$.nombre");
        assertThat(nombre).isEqualTo("BK_agrodb_test_" + dia + ".sql");
        String sql = Files.readString(carpeta.resolve(nombre));
        assertThat(sql).contains("CREATE TABLE public.articulo").contains("COPY public.articulo").contains("Urea 46% saco 50 kg")
                .contains("DROP TABLE IF EXISTS public.articulo");
        assertThat(Files.list(carpeta).map(p -> p.getFileName().toString()).toList()).containsExactly(nombre);   // sin temporales

        String estado = getJson("/api/v1/admin/respaldos");
        assertThat(JsonPath.<List<String>>read(estado, "$.respaldos[*].nombre")).containsExactly(nombre);
        assertThat(JsonPath.<Boolean>read(estado, "$.respaldoDeHoy")).isTrue();
        // El automático no repite el respaldo del día
        long antes = Files.getLastModifiedTime(carpeta.resolve(nombre)).toMillis();
        respaldos.programado();
        assertThat(Files.getLastModifiedTime(carpeta.resolve(nombre)).toMillis()).isEqualTo(antes);
        var descarga = mvc.perform(get("/api/v1/admin/respaldos/" + nombre).header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(descarga.getStatus()).isEqualTo(200);
        assertThat(descarga.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(sql);
        assertThat(mvc.perform(get("/api/v1/admin/respaldos/..%2F..%2Fetc%2Fpasswd").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getStatus()).isIn(400, 422);
    }

    // ------------------------------------------------------------------ helpers
    private String post(String token, String url, String body, int esperado) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as("POST %s -> %s", url, res.getContentAsString()).isEqualTo(esperado);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private void put(String url, String body) throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                .header("Authorization", "Bearer " + admin).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as("PUT %s -> %s", url, res.getContentAsString()).isEqualTo(200);
    }

    private String putCodigo(String url, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                .header("Authorization", "Bearer " + admin).contentType("application/json").content(body)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String getJson(String url) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(res.getStatus()).as("GET %s -> %s", url, res.getContentAsString()).isEqualTo(200);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static String code(String json) { return JsonPath.read(json, "$.code"); }
}
