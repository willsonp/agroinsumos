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
    public Reserva reservar(Long companiaId, TipoEcf tipo) {
        EcfSecuencia s = repo.bloquearActiva(companiaId, (short) tipo.codigo())
                .orElseThrow(() -> new BusinessException("SECUENCIA_NO_CONFIGURADA",
                        "No hay secuencia e-NCF activa para el tipo " + tipo.codigo()));
        if (s.getFechaVencimiento().isBefore(LocalDate.now(clock))) {
            throw new BusinessException("SECUENCIA_VENCIDA", "La secuencia e-NCF tipo " + tipo.codigo() + " está vencida");
        }
        long siguiente = Math.max(s.getUltimaUsada(), s.getSecuenciaDesde() - 1) + 1;
        if (siguiente > s.getSecuenciaHasta()) {
            throw new BusinessException("SECUENCIA_AGOTADA", "Se agotó la secuencia e-NCF tipo " + tipo.codigo()
                    + ". Solicite un nuevo rango en la Oficina Virtual de la DGII");
        }
        s.setUltimaUsada(siguiente);
        return new Reserva(formatear(tipo, siguiente), s.getFechaVencimiento());
    }

    public static String formatear(TipoEcf tipo, long secuencia) {
        return "E%02d%010d".formatted(tipo.codigo(), secuencia);
    }

    @Transactional(readOnly = true)
    public List<SecuenciaResponse> listar(Long companiaId) {
        return repo.findByCompaniaIdOrderByTipoEcfAscIdDesc(companiaId).stream().map(SecuenciaResponse::de).toList();
    }

    /** Registra un nuevo rango autorizado; desactiva el anterior del mismo tipo. */
    @Transactional
    public SecuenciaResponse registrar(Long companiaId, SecuenciaRequest req) {
        TipoEcf tipo = TipoEcf.desdeCodigo(req.tipoEcf());
        if (req.secuenciaHasta() < req.secuenciaDesde()) {
            throw new BusinessException("RANGO_INVALIDO", "secuenciaHasta debe ser >= secuenciaDesde");
        }
        repo.findByCompaniaIdAndTipoEcfAndActivaTrue(companiaId, (short) tipo.codigo()).ifPresent(prev -> {
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
        s.setSecuenciaDesde(req.secuenciaDesde());
        s.setSecuenciaHasta(req.secuenciaHasta());
        s.setFechaVencimiento(req.fechaVencimiento());
        return SecuenciaResponse.de(repo.save(s));
    }
}
