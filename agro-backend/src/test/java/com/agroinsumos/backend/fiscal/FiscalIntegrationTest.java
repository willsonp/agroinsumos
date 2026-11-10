package com.agroinsumos.backend.fiscal;

import com.agroinsumos.backend.IntegrationTestBase;
import com.agroinsumos.backend.ecf.xml.XmlUtil;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** e-CF 41 (compras a informales), e-CF 43 (gastos menores) y formatos 606/607. */
class FiscalIntegrationTest extends IntegrationTestBase {

    static final ZoneId RD = ZoneId.of("America/Santo_Domingo");
    String admin, periodo, hoy;
    long urea, fumigacion, formal, informal, finca, consumidor;

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        admin = login("admin", "adminclave");
        periodo = YearMonth.now(RD).format(DateTimeFormatter.ofPattern("yyyyMM"));
        hoy = LocalDate.now(RD).format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int t : new int[]{31, 32, 33, 34, 41, 43}) {
            post("/api/v1/ecf/secuencias",
                    "{\"tipoEcf\":%d,\"secuenciaDesde\":1,\"secuenciaHasta\":500,\"fechaVencimiento\":\"2030-12-31\"}".formatted(t), 201);
        }
        post("/api/v1/catalogos/almacenes", "{\"nombre\":\"Principal\",\"principal\":true}", 201);
        long unidad = id(post("/api/v1/catalogos/unidades", "{\"nombre\":\"Unidad\"}", 201));
        long depto = id(post("/api/v1/catalogos/departamentos", "{\"nombre\":\"General\",\"tipo\":\"I\"}", 201));
        urea = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Urea","tipo":"B","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":1000,
                 "precioContado":2100,"precioCredito":2100}""".formatted(depto, unidad), 201));
        fumigacion = id(post("/api/v1/inventario/articulos", """
                {"nombre":"Servicio de fumigación","tipo":"S","departamentoId":%d,"unidadId":%d,"indicadorItbis":1,"costo":0,
                 "precioContado":1}""".formatted(depto, unidad), 201));
        formal = id(post("/api/v1/catalogos/proveedores", "{\"nombre\":\"Fertilizantes del Cibao\",\"rncCedula\":\"131000002\"}", 201));
        informal = id(post("/api/v1/catalogos/proveedores", "{\"nombre\":\"Juan Agricultor\",\"rncCedula\":\"001-1234567-8\"}", 201));
        finca = id(post("/api/v1/catalogos/clientes", """
                {"nombre":"Finca El Progreso","rncCedula":"101010101","tipoEcfPreferido":31,"tipoPrecio":"CREDITO",
                 "limiteCredito":0,"diasCredito":30}""", 201));
        consumidor = id(post("/api/v1/catalogos/clientes",
                "{\"nombre\":\"Consumidor final\",\"tipoEcfPreferido\":32,\"tipoPrecio\":\"CONTADO\",\"limiteCredito\":0}", 201));
    }

    @Test
    void ecf41CompraAInformalConRetenciones() throws Exception {
        String c = post("/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CONTADO","tipoBienServicio":"02","emitirComprobanteCompras":true,
                 "itbisRetenido":900,"isrRetenido":500,"tipoRetencionIsr":"02",
                 "lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":5000}]}""".formatted(informal, fumigacion), 201);
        assertThat(JsonPath.<String>read(c, "$.encfCompra")).isEqualTo("E410000000001");
        assertThat(num(c, "$.total")).isEqualTo(5900.0);
        assertThat(num(c, "$.aPagar")).isEqualTo(4500.0);

        String xml = xml(JsonPath.<Integer>read(c, "$.ecfDocumentoId"));
        assertThat(xml).contains("<TipoeCF>41</TipoeCF>", "<RNCComprador>00112345678</RNCComprador>",
                "<RazonSocialComprador>JUAN AGRICULTOR</RazonSocialComprador>",
                "<Retencion><IndicadorAgenteRetencionoPercepcion>1</IndicadorAgenteRetencionoPercepcion>"
                        + "<MontoITBISRetenido>900.00</MontoITBISRetenido><MontoISRRetenido>500.00</MontoISRRetenido></Retencion>",
                "<MontoTotal>5900.00</MontoTotal><TotalITBISRetenido>900.00</TotalITBISRetenido><TotalISRRetencion>500.00</TotalISRRetencion>")
                .doesNotContain("<TipoIngresos>");
        assertThat(firmaValida(xml)).isTrue();

        // Suplidor que entrega NCF -> no se emite 41; suplidor sin cédula -> error
        assertThat(code(post("/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000001","condicion":"CONTADO","emitirComprobanteCompras":true,
                 "lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":10}]}""".formatted(formal, urea), 422))).isEqualTo("NCF_NO_APLICA");
        long sinCedula = id(post("/api/v1/catalogos/proveedores", "{\"nombre\":\"Pedro\"}", 201));
        assertThat(code(post("/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CONTADO","emitirComprobanteCompras":true,
                 "lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":10}]}""".formatted(sinCedula, urea), 422))).isEqualTo("CEDULA_REQUERIDA");
        // ISR retenido exige tipo de retención (606 campo 17)
        assertThat(code(post("/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CONTADO","isrRetenido":10,"ncfProveedor":"B1100000001",
                 "lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":100}]}""".formatted(informal, fumigacion), 422))).isEqualTo("TIPO_RETENCION_REQUERIDO");
    }

    @Test
    void ecf43GastoMenorDesdeCaja() throws Exception {
        post("/api/v1/caja/sesiones/abrir", "{\"montoInicial\":1000}", 201);
        String e = post("/api/v1/caja/sesiones/actual/egresos",
                "{\"beneficiario\":\"Peaje Las Américas\",\"concepto\":\"Peaje camión de reparto\",\"monto\":300,\"emitirGastoMenor\":true}", 201);
        long ecfId = jdbc.queryForObject("select ecf_documento_id from caja_egreso", Long.class);
        assertThat(jdbc.queryForObject("select ncf from caja_egreso", String.class)).isEqualTo("E430000000001");
        String xml = xml(ecfId);
        assertThat(xml).contains("<TipoeCF>43</TipoeCF>", "<IndicadorFacturacion>4</IndicadorFacturacion>", "<MontoTotal>300.00</MontoTotal>")
                .doesNotContain("<Comprador>", "<TipoIngresos>", "<IndicadorMontoGravado>");
        assertThat(firmaValida(xml)).isTrue();
        assertThat(code(post("/api/v1/caja/sesiones/actual/egresos",
                "{\"beneficiario\":\"X\",\"concepto\":\"Y\",\"monto\":118,\"itbis\":18,\"emitirGastoMenor\":true}", 422))).isEqualTo("GASTO_MENOR_INVALIDO");
        assertThat(e).contains("EGRESO");
    }

    @Test
    void formato606() throws Exception {
        // 1) Compra formal a crédito, pagada por transferencia
        String c1 = post("/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000010","condicion":"CREDITO",
                 "lineas":[{"articuloId":%d,"cantidad":10,"costoUnitario":1000}]}""".formatted(formal, urea), 201);
        post("/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":11800}],"formas":[{"forma":"TRANSFERENCIA","monto":11800}]}
                """.formatted(formal, id(c1)), 201);
        // 2) Compra a informal con e-CF 41 y retenciones, pagada por transferencia
        String c2 = post("/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CONTADO","tipoBienServicio":"02","emitirComprobanteCompras":true,
                 "itbisRetenido":900,"isrRetenido":500,"tipoRetencionIsr":"02",
                 "lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":5000}]}""".formatted(informal, fumigacion), 201);
        post("/api/v1/cxp/pagos", """
                {"proveedorId":%d,"aplicaciones":[{"compraId":%d,"monto":4500}],"formas":[{"forma":"TRANSFERENCIA","monto":4500}]}
                """.formatted(informal, id(c2)), 201);
        // 3) Compra sin comprobante -> advertencia
        post("/api/v1/compras", """
                {"proveedorId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":1,"costoUnitario":50}]}""".formatted(formal, urea), 201);
        // 4) Egresos: con NCF del proveedor y con e-CF 43
        post("/api/v1/caja/sesiones/abrir", "{\"montoInicial\":5000}", 201);
        post("/api/v1/caja/sesiones/actual/egresos", """
                {"beneficiario":"Ferretería","rnc":"101010101","ncf":"B0100000099","concepto":"Candados","monto":1180,"itbis":180}""", 201);
        post("/api/v1/caja/sesiones/actual/egresos", "{\"beneficiario\":\"Peaje\",\"concepto\":\"Peaje\",\"monto\":300,\"emitirGastoMenor\":true}", 201);
        // Sin comprobante -> advertencia
        post("/api/v1/caja/sesiones/actual/egresos", "{\"beneficiario\":\"Colmado\",\"concepto\":\"Agua\",\"monto\":50}", 201);

        String rep = getJson("/api/v1/fiscal/606?periodo=" + periodo);
        assertThat(JsonPath.<List<?>>read(rep, "$.lineas")).hasSize(4);
        assertThat(JsonPath.<List<String>>read(rep, "$.advertencias")).hasSize(2);
        assertThat(num(rep, "$.resumen.totalFacturado")).isEqualTo(10000 + 5000 + 1000 + 300.0);
        assertThat(num(rep, "$.resumen.itbisRetenido")).isEqualTo(900.0);
        assertThat(num(rep, "$.resumen.isrRetenido")).isEqualTo(500.0);

        String txt = getTxt("/api/v1/fiscal/606/txt?periodo=" + periodo, "DGII_F_606_131000001_" + periodo + ".TXT");
        String[] l = txt.split("\r\n");
        assertThat(l[0]).isEqualTo("606|131000001|" + periodo + "|4");
        assertThat(l[1]).isEqualTo("131000002|1|09|B0100000010||%s|%s|0.00|10000.00|10000.00|1800.00||||1800.00||||||||04".formatted(hoy, hoy));
        assertThat(l[2]).isEqualTo("00112345678|2|02|E410000000001||%s|%s|5000.00|0.00|5000.00|900.00|900.00|||900.00||02|500.00|||||02".formatted(hoy, hoy));
        assertThat(l[3]).isEqualTo("101010101|1|02|B0100000099||%s|%s|1000.00|0.00|1000.00|180.00||||180.00||||||||01".formatted(hoy, hoy));
        assertThat(l[4]).isEqualTo("131000001|1|02|E430000000001||%s|%s|300.00|0.00|300.00|0.00||||||||||||01".formatted(hoy, hoy));
        for (String linea : List.of(l).subList(1, l.length)) assertThat(linea.split("\\|", -1)).hasSize(23);

        assertThat(code(getError("/api/v1/fiscal/606?periodo=2026-11"))).isEqualTo("PERIODO_INVALIDO");
    }

    @Test
    void formato607() throws Exception {
        post("/api/v1/compras", """
                {"proveedorId":%d,"ncfProveedor":"B0100000010","condicion":"CREDITO",
                 "lineas":[{"articuloId":%d,"cantidad":50,"costoUnitario":1000}]}""".formatted(formal, urea), 201);
        // F1: crédito fiscal a crédito; abono por transferencia con retención de ITBIS
        String f1 = post("/api/v1/ventas/facturas", """
                {"clienteId":%d,"condicion":"CREDITO","lineas":[{"articuloId":%d,"cantidad":5}]}""".formatted(finca, urea), 201);
        post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":5000,"retencionItbis":567}],
                 "pagos":[{"forma":"TRANSFERENCIA","monto":5000}]}""".formatted(finca, id(f1)), 201);
        // Recibo anulado: no debe contar
        post("/api/v1/caja/sesiones/abrir", "{\"montoInicial\":0}", 201);
        String anulado = post("/api/v1/cxc/recibos", """
                {"clienteId":%d,"aplicaciones":[{"documentoTipo":"FACTURA","documentoId":%d,"monto":100}],
                 "pagos":[{"forma":"EFECTIVO","monto":100}]}""".formatted(finca, id(f1)), 201);
        post("/api/v1/cxc/recibos/" + id(anulado) + "/anular", "{\"motivo\":\"error\"}", 200);
        // F2: consumo en POS, efectivo con devuelta + tarjeta
        String v = post("/api/v1/caja/ventas", """
                {"factura":{"clienteId":%d,"condicion":"CONTADO","lineas":[{"articuloId":%d,"cantidad":2}]},
                 "pagos":[{"forma":"EFECTIVO","monto":3000},{"forma":"TARJETA","monto":2000}]}""".formatted(consumidor, urea), 201);
        // Nota de crédito sobre F2 y nota de débito sobre F1
        post("/api/v1/ventas/devoluciones", """
                {"facturaId":%d,"motivo":"Sobrante","lineas":[{"facturaLineaId":%d,"cantidad":1}]}"""
                .formatted(JsonPath.<Integer>read(v, "$.factura.id"), JsonPath.<Integer>read(v, "$.factura.lineas[0].id")), 201);
        post("/api/v1/cxc/notas-debito", "{\"facturaId\":%d,\"concepto\":\"Mora\",\"monto\":250}".formatted(id(f1)), 201);
        // Factura histórica migrada con NCF en papel (y una anulada, que va al 608)
        jdbc.update("""
                insert into factura(compania_id, numero, fecha, condicion, cliente_id, cliente_nombre, cliente_rnc, almacen_id,
                                    tipo_precio, monto_gravado, total_itbis, total, balance, ncf_legacy, estado)
                values (?, 9001, ?, 'CONTADO', ?, 'FINCA', '101010101', (select id from almacen limit 1), 'CONTADO',
                        1000, 180, 1180, 0, 'B0100000123', 'PAGADA'),
                       (?, 9002, ?, 'CONTADO', ?, 'FINCA', '101010101', (select id from almacen limit 1), 'CONTADO',
                        1000, 180, 1180, 0, 'B0100000124', 'ANULADA')""",
                companiaId, LocalDate.now(RD), finca, companiaId, LocalDate.now(RD), finca);

        String rep = getJson("/api/v1/fiscal/607?periodo=" + periodo);
        assertThat(JsonPath.<List<?>>read(rep, "$.lineas")).hasSize(5);
        assertThat(num(rep, "$.resumen.notasCredito")).isEqualTo(2478.0);

        String txt = getTxt("/api/v1/fiscal/607/txt?periodo=" + periodo, "DGII_F_607_131000001_" + periodo + ".TXT");
        String[] l = txt.split("\r\n");
        assertThat(l[0]).isEqualTo("607|131000001|" + periodo + "|5");
        List<String> lineas = List.of(l).subList(1, l.length);
        // F1 (venta a crédito): 10500 + 1890 = 12390; retenido 567 -> venta a crédito 11823
        assertThat(lineas).contains("101010101|1|E310000000001||01|%s|%s|10500.00|1890.00|567.00||||||||||11823.00|||".formatted(hoy, hoy));
        // F2 (contado): 4200 + 756; efectivo neto 3000 - 44 = 2956, tarjeta 2000
        assertThat(lineas).contains("||E320000000001||01|%s||4200.00|756.00||||||||2956.00||2000.00||||".formatted(hoy));
        assertThat(lineas).contains("||E340000000001|E320000000001|01|%s||2100.00|378.00||||||||||||||".formatted(hoy));
        assertThat(lineas).contains("101010101|1|E330000000001|E310000000001|01|%s||250.00|0.00|||||||||||250.00|||".formatted(hoy));
        // Factura migrada de contado sin recibo: se presume efectivo
        assertThat(lineas).contains("101010101|1|B0100000123||01|%s||1000.00|180.00||||||||1180.00||||||".formatted(hoy));
        assertThat(txt).doesNotContain("B0100000124");
        for (String linea : lineas) assertThat(linea.split("\\|", -1)).hasSize(23);

        // Excluyendo e-CF solo queda la factura con NCF en papel
        String sinEcf = getJson("/api/v1/fiscal/607?periodo=" + periodo + "&incluirEcf=false");
        assertThat(JsonPath.<List<?>>read(sinEcf, "$.lineas")).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers
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

    private String getError(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + admin)).andReturn().getResponse().getContentAsString();
    }

    private String getTxt(String url, String nombre) throws Exception {
        var res = mvc.perform(get(url).header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("Content-Disposition")).contains(nombre);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private String xml(Number ecfId) throws Exception {
        return mvc.perform(get("/api/v1/ecf/documentos/" + ecfId + "/xml").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static boolean firmaValida(String xml) throws Exception {
        var doc = XmlUtil.parsear(xml.getBytes(StandardCharsets.UTF_8));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream("src/test/resources/ecf/test-cert.p12")) { ks.load(in, "test1234".toCharArray()); }
        var nodo = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        var ctx = new DOMValidateContext(((X509Certificate) ks.getCertificate("test")).getPublicKey(), nodo);
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
    }

    private static long id(String json) { return JsonPath.<Number>read(json, "$.id").longValue(); }

    private static double num(String json, String path) { return JsonPath.<Number>read(json, path).doubleValue(); }

    private static String code(String json) { return JsonPath.read(json, "$.code"); }
}
