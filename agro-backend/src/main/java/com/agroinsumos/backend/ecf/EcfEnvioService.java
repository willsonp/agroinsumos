package com.agroinsumos.backend.ecf;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.ecf.dgii.DgiiClient;
import com.agroinsumos.backend.ecf.dgii.DgiiClient.Mensaje;
import com.agroinsumos.backend.ecf.dto.EcfDtos.EcfResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.List;

/**
 * Envío a la DGII y seguimiento del resultado. Las llamadas HTTP se hacen FUERA de
 * transacciones de BD; antes de enviar se "reclama" el documento con un UPDATE condicional
 * para que dos procesos (botón + tarea programada, o varias instancias) no lo envíen dos veces.
 * Si la DGII no está disponible el documento queda en ERROR_ENVIO y se reintenta (contingencia).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcfEnvioService {

    private final EcfDocumentoRepository documentos;
    private final DgiiClient dgii;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final EcfProperties props;

    public EcfResponse enviar(Long companiaId, Long id) {
        var d = documentos.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("e-CF", id));
        if (!d.getEstado().pendienteDeEnvio()) {
            throw new BusinessException("ECF_YA_ENVIADO", "El e-CF " + d.getEncf() + " está en estado " + d.getEstado());
        }
        if (!reclamar(id)) {
            throw new BusinessException("ECF_ENVIO_EN_CURSO", "El e-CF " + d.getEncf() + " se está enviando en este momento");
        }
        procesarEnvio(id);
        return EcfResponse.de(documentos.findById(id).orElseThrow());
    }

    public EcfResponse consultar(Long companiaId, Long id) {
        var d = documentos.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("e-CF", id));
        if (!d.getEstado().pendienteDeResultado()) return EcfResponse.de(d);
        procesarConsulta(id);
        return EcfResponse.de(documentos.findById(id).orElseThrow());
    }

    /** Reintentos automáticos (contingencia) de documentos firmados no enviados. */
    @Scheduled(fixedDelayString = "${app.ecf.consulta-estado-intervalo:60s}", initialDelayString = "30s")
    public void tareaPeriodica() {
        if (props.envioAutomatico()) {
            for (Long id : documentos.idsPorEstado(EnumSet.of(EstadoEcf.FIRMADO, EstadoEcf.ERROR_ENVIO),
                    props.maxIntentosEnvio(), PageRequest.of(0, 50))) {
                if (reclamar(id)) procesarEnvio(id);
            }
        }
        for (Long id : documentos.idsPorEstado(EnumSet.of(EstadoEcf.ENVIADO, EstadoEcf.EN_PROCESO),
                Integer.MAX_VALUE, PageRequest.of(0, 100))) {
            procesarConsulta(id);
        }
    }

    private boolean reclamar(Long id) {
        return jdbc.update("""
                update ecf_documento set enviado_en = now(), intentos_envio = intentos_envio + 1
                where id = ? and estado in ('FIRMADO','ERROR_ENVIO')
                  and (enviado_en is null or enviado_en < now() - interval '2 minutes')
                """, id) == 1;
    }

    private void procesarEnvio(Long id) {
        var d = documentos.findById(id).orElseThrow();
        String archivo = rncEmisor(d) + d.getEncf() + ".xml";
        try {
            if (d.isEsResumenConsumo()) {
                var r = dgii.enviarRfce(d.getCompaniaId(), archivo, d.getXmlRfceFirmado());
                actualizar(id, doc -> {
                    doc.setEstado(mapear(r.codigo(), r.estado()));
                    doc.setSecuenciaUtilizada(r.secuenciaUtilizada());
                    doc.setMensajesDgii(aJson(r.mensajes()));
                    doc.setUltimoError(null);
                });
            } else {
                var r = dgii.enviarEcf(d.getCompaniaId(), archivo, d.getXmlFirmado());
                if (r == null || r.trackId() == null) {
                    throw new IllegalStateException("Respuesta sin trackId: " + (r == null ? "vacía" : r.mensaje()));
                }
                actualizar(id, doc -> {
                    doc.setEstado(EstadoEcf.ENVIADO);
                    doc.setTrackId(r.trackId());
                    doc.setUltimoError(null);
                });
            }
            log.info("e-CF {} enviado a la DGII", d.getEncf());
        } catch (Exception e) {
            log.warn("Error enviando e-CF {}: {}", d.getEncf(), e.getMessage());
            actualizar(id, doc -> {
                doc.setEstado(EstadoEcf.ERROR_ENVIO);
                doc.setUltimoError(truncar(e.getMessage()));
            });
        }
    }

    private void procesarConsulta(Long id) {
        var d = documentos.findById(id).orElseThrow();
        try {
            var r = dgii.consultarEstado(d.getCompaniaId(), d.getTrackId());
            if (r == null) return;
            EstadoEcf nuevo = mapear(r.codigo(), r.estado());
            actualizar(id, doc -> {
                doc.setEstado(nuevo);
                doc.setSecuenciaUtilizada(r.secuenciaUtilizada());
                doc.setMensajesDgii(aJson(r.mensajes()));
            });
            if (nuevo == EstadoEcf.RECHAZADO) log.warn("e-CF {} RECHAZADO por la DGII: {}", d.getEncf(), r.mensajes());
        } catch (Exception e) {
            log.warn("Error consultando estado de e-CF {}: {}", d.getEncf(), e.getMessage());
        }
    }

    /** Códigos DGII: 0 no encontrado, 1 aceptado, 2 rechazado, 3 en proceso, 4 aceptado condicional. */
    static EstadoEcf mapear(Integer codigo, String estado) {
        if (codigo != null) {
            switch (codigo) {
                case 1: return EstadoEcf.ACEPTADO;
                case 2: return EstadoEcf.RECHAZADO;
                case 4: return EstadoEcf.ACEPTADO_CONDICIONAL;
                default: return EstadoEcf.EN_PROCESO;
            }
        }
        String e = estado == null ? "" : estado.toLowerCase();
        if (e.contains("condicional")) return EstadoEcf.ACEPTADO_CONDICIONAL;
        if (e.startsWith("aceptado")) return EstadoEcf.ACEPTADO;
        if (e.startsWith("rechazado")) return EstadoEcf.RECHAZADO;
        return EstadoEcf.EN_PROCESO;
    }

    private void actualizar(Long id, java.util.function.Consumer<EcfDocumento> cambio) {
        tx.executeWithoutResult(s -> cambio.accept(documentos.findById(id).orElseThrow()));
    }

    private String rncEmisor(EcfDocumento d) {
        return jdbc.queryForObject("select rnc from compania where id = ?", String.class, d.getCompaniaId());
    }

    private String aJson(List<Mensaje> mensajes) {
        return mensajes == null ? null : json.writeValueAsString(mensajes);
    }

    private static String truncar(String s) {
        return s == null ? null : s.substring(0, Math.min(1000, s.length()));
    }
}
