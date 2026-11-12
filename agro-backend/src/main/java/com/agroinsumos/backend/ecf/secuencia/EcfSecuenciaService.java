package com.agroinsumos.backend.ecf.secuencia;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.ecf.TipoEcf;
import com.agroinsumos.backend.ecf.dto.EcfDtos.SecuenciaRequest;
import com.agroinsumos.backend.ecf.dto.EcfDtos.SecuenciaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Reemplaza el procedimiento ConsultaNCF_Secuencia, que hacía SELECT MAX + UPDATE sin
 * bloqueo ni filtro por compañía (dos cajas podían obtener el mismo NCF).
 */
@Service
@RequiredArgsConstructor
public class EcfSecuenciaService {

    private final EcfSecuenciaRepository repo;
    private final Clock clock;

    public record Reserva(String encf, LocalDate fechaVencimiento) {}

    /** Debe ejecutarse dentro de la transacción de emisión: si la firma falla, el número no se consume. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reserva reservar(Long companiaId, TipoEcf tipo) { return reservar(companiaId, tipo, "E"); }

    /** serie 'E' = e-NCF; 'B' = NCF tradicional (B01 = tipo 31, B02 = 32, B04 = 34...). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reserva reservar(Long companiaId, TipoEcf tipo, String serie) {
        String nombre = nombre(tipo, serie);
        EcfSecuencia s = repo.bloquearActiva(companiaId, serie, (short) tipo.codigo())
                .orElseThrow(() -> new BusinessException("SECUENCIA_NO_CONFIGURADA",
                        "No hay secuencia " + nombre + " activa. Regístrela en Fiscal > Secuencias"));
        if (s.getFechaVencimiento().isBefore(LocalDate.now(clock))) {
            throw new BusinessException("SECUENCIA_VENCIDA", "La secuencia " + nombre + " está vencida");
        }
        long siguiente = Math.max(s.getUltimaUsada(), s.getSecuenciaDesde() - 1) + 1;
        if (siguiente > s.getSecuenciaHasta()) {
            throw new BusinessException("SECUENCIA_AGOTADA", "Se agotó la secuencia " + nombre
                    + ". Solicite un nuevo rango en la Oficina Virtual de la DGII");
        }
        s.setUltimaUsada(siguiente);
        return new Reserva("B".equals(serie) ? formatearNcf(tipo, siguiente) : formatear(tipo, siguiente), s.getFechaVencimiento());
    }

    public static String formatear(TipoEcf tipo, long secuencia) {
        return "E%02d%010d".formatted(tipo.codigo(), secuencia);
    }

    /** NCF tradicional: B + tipo (01, 02, 04, 11, 13, 14, 15...) + 8 dígitos. */
    public static String formatearNcf(TipoEcf tipo, long secuencia) {
        return "B%02d%08d".formatted(tipo.codigo() - 30, secuencia);
    }

    private static String nombre(TipoEcf tipo, String serie) {
        return "B".equals(serie) ? "NCF B%02d".formatted(tipo.codigo() - 30) : "e-NCF tipo " + tipo.codigo();
    }

    @Transactional(readOnly = true)
    public List<SecuenciaResponse> listar(Long companiaId) {
        return repo.findByCompaniaIdOrderByTipoEcfAscIdDesc(companiaId).stream().map(SecuenciaResponse::de).toList();
    }

    /** Registra un nuevo rango autorizado; desactiva el anterior del mismo tipo. */
    @Transactional
    public SecuenciaResponse registrar(Long companiaId, SecuenciaRequest req) {
        TipoEcf tipo = TipoEcf.desdeCodigo(req.tipoEcf());
        String serie = req.serie() == null ? "E" : req.serie();
        if (req.secuenciaHasta() < req.secuenciaDesde()) {
            throw new BusinessException("RANGO_INVALIDO", "secuenciaHasta debe ser >= secuenciaDesde");
        }
        if ("B".equals(serie) && req.secuenciaHasta() > 99_999_999L) {
            throw new BusinessException("RANGO_INVALIDO", "Los NCF serie B tienen 8 dígitos (hasta 99999999)");
        }
        repo.findByCompaniaIdAndSerieAndTipoEcfAndActivaTrue(companiaId, serie, (short) tipo.codigo()).ifPresent(prev -> {
            if (req.secuenciaDesde() <= prev.getUltimaUsada()) {
                throw new BusinessException("RANGO_SOLAPADO", "El nuevo rango se solapa con números ya utilizados (última: "
                        + prev.getUltimaUsada() + ")");
            }
            prev.setActiva(false);
            repo.flush();
        });
        var s = new EcfSecuencia();
        s.setCompaniaId(companiaId);
        s.setTipoEcf((short) tipo.codigo());
        s.setSerie(serie);
        s.setSecuenciaDesde(req.secuenciaDesde());
        s.setSecuenciaHasta(req.secuenciaHasta());
        s.setFechaVencimiento(req.fechaVencimiento());
        return SecuenciaResponse.de(repo.save(s));
    }
}
