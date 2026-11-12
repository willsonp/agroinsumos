package com.agroinsumos.backend.ecf.emision;

import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.compania.CompaniaRepository;
import com.agroinsumos.backend.ecf.TipoEcf;
import com.agroinsumos.backend.ecf.dto.EcfDtos.EmitirEcfRequest;
import com.agroinsumos.backend.ecf.secuencia.EcfSecuenciaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Comprobante fiscal de un documento (factura, nota de crédito/débito, comprobante de compras, gasto menor) según
 * el modo de la empresa:
 * <ul>
 *   <li><b>ECF</b>: e-CF firmado (y enviado a la DGII) — {@link EcfEmisionService}.</li>
 *   <li><b>NCF</b>: siguiente NCF tradicional serie B del rango autorizado (B01, B02, B04...), con las mismas reglas
 *       fiscales (RNC del comprador, referencia de las notas); sin firma ni envío.</li>
 *   <li><b>NINGUNO</b>: sin comprobante fiscal (el documento solo lleva su número interno).</li>
 * </ul>
 * Los módulos que emiten documentos llaman aquí y no al e-CF directamente.
 */
@Service
@RequiredArgsConstructor
public class ComprobanteFiscalService {

    public enum Modo { ECF, NCF, NINGUNO }

    /**
     * @param numero         e-NCF (E31...) o NCF (B01...); null sin comprobante
     * @param ecfDocumentoId solo en modo ECF (para el estado, el XML y el timbre)
     */
    public record Comprobante(String numero, Long ecfDocumentoId, BigDecimal totalItbis, BigDecimal montoTotal) {}

    private final CompaniaRepository companias;
    private final EcfEmisionService ecf;
    private final EcfSecuenciaService secuencias;

    public Modo modo(Long companiaId) {
        var c = companias.findById(companiaId).orElseThrow(() -> new NotFoundException("Compañía", companiaId));
        return Modo.valueOf(c.getModoComprobante());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Comprobante emitir(Long companiaId, EmitirEcfRequest req) {
        return switch (modo(companiaId)) {
            case ECF -> {
                var r = ecf.emitir(companiaId, req);
                yield new Comprobante(r.encf(), r.id(), r.totalItbis(), r.montoTotal());
            }
            case NCF -> {
                EcfCalculadora.Totales t = ecf.validar(req);
                var reserva = secuencias.reservar(companiaId, TipoEcf.desdeCodigo(req.tipoEcf()), "B");
                yield new Comprobante(reserva.encf(), null, t.totalItbis(), t.montoTotal());
            }
            case NINGUNO -> {
                var t = EcfCalculadora.calcular(req.items());
                yield new Comprobante(null, null, t.totalItbis(), t.montoTotal());
            }
        };
    }
}
