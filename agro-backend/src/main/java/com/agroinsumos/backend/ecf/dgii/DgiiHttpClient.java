package com.agroinsumos.backend.ecf.dgii;

import com.agroinsumos.backend.ecf.EcfProperties;
import com.agroinsumos.backend.ecf.firma.CertificadoProvider;
import com.agroinsumos.backend.ecf.firma.XmlFirmador;
import com.agroinsumos.backend.ecf.xml.XmlUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente HTTP de los servicios e-CF de la DGII:
 * <ol>
 *   <li>Autenticación: GET semilla -> firmar -> POST validarsemilla -> token Bearer (cache hasta expirar).</li>
 *   <li>Recepción e-CF: POST multipart "xml" -> trackId.</li>
 *   <li>Consulta de resultado por trackId.</li>
 *   <li>Recepción RFCE (consumo &lt; 250k) en fc.dgii.gov.do.</li>
 * </ol>
 * Las rutas se basan en la "Descripción Técnica de Facturación Electrónica"; verifíquelas
 * contra la documentación vigente de la DGII antes de la certificación.
 */
@Slf4j
@Component
public class DgiiHttpClient implements DgiiClient {

    private final EcfProperties props;
    private final CertificadoProvider certificados;
    private final XmlFirmador firmador;
    private final RestClient http;
    private final Map<Long, Token> tokens = new ConcurrentHashMap<>();

    record Token(String valor, Instant expira) {}

    record TokenResponse(String token, String expira, String expedido) {}

    public DgiiHttpClient(EcfProperties props, CertificadoProvider certificados, XmlFirmador firmador,
                          RestClient.Builder builder) {
        this.props = props;
        this.certificados = certificados;
        this.firmador = firmador;
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        rf.setReadTimeout(Duration.ofSeconds(60));
        this.http = builder.requestFactory(rf).build();
    }

    @Override
    public RecepcionResultado enviarEcf(Long companiaId, String nombreArchivo, String xml) {
        return http.post().uri(props.baseEcf() + "/recepcion/api/facturaselectronicas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(companiaId))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(nombreArchivo, xml))
                .retrieve().body(RecepcionResultado.class);
    }

    @Override
    public EstadoResultado consultarEstado(Long companiaId, String trackId) {
        return http.get().uri(props.baseEcf() + "/consultaresultado/api/consultas/estado?trackid={t}", trackId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(companiaId))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(EstadoResultado.class);
    }

    @Override
    public RfceResultado enviarRfce(Long companiaId, String nombreArchivo, String xml) {
        return http.post().uri(props.baseFc() + "/recepcionfc/api/recepcion/ecf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(companiaId))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(nombreArchivo, xml))
                .retrieve().body(RfceResultado.class);
    }

    private String token(Long companiaId) {
        Token t = tokens.get(companiaId);
        if (t != null && t.expira().isAfter(Instant.now().plusSeconds(60))) return t.valor();
        t = autenticar(companiaId);
        tokens.put(companiaId, t);
        return t.valor();
    }

    private Token autenticar(Long companiaId) {
        String base = props.baseEcf() + "/autenticacion/api/autenticacion";
        byte[] semilla = http.get().uri(base + "/semilla").accept(MediaType.APPLICATION_XML)
                .retrieve().body(byte[].class);
        var firmada = firmador.firmar(XmlUtil.parsear(semilla), certificados.obtener(companiaId));
        TokenResponse r = http.post().uri(base + "/validarsemilla")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart("semilla.xml", XmlUtil.serializar(firmada.documento())))
                .retrieve().body(TokenResponse.class);
        if (r == null || r.token() == null) throw new IllegalStateException("La DGII no devolvió token");
        Instant expira = parsearExpira(r.expira());
        log.info("Token DGII obtenido para compañía {} (expira {})", companiaId, expira);
        return new Token(r.token(), expira);
    }

    private static Instant parsearExpira(String expira) {
        try {
            return java.time.OffsetDateTime.parse(expira).toInstant();
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(expira).atZone(java.time.ZoneId.of("America/Santo_Domingo")).toInstant();
            } catch (Exception e2) {
                return Instant.now().plus(Duration.ofMinutes(50)); // el token DGII dura ~1 hora
            }
        }
    }

    private static LinkedMultiValueMap<String, Object> multipart(String nombreArchivo, String xml) {
        var parte = new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return nombreArchivo; }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("xml", parte);
        return body;
    }
}
