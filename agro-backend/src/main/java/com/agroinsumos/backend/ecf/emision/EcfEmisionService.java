package com.agroinsumos.backend.ecf.emision;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.compania.CompaniaRepository;
import com.agroinsumos.backend.ecf.*;
import com.agroinsumos.backend.ecf.dto.EcfDtos.EcfResponse;
import com.agroinsumos.backend.ecf.dto.EcfDtos.EmitirEcfRequest;
import com.agroinsumos.backend.ecf.firma.CertificadoProvider;
import com.agroinsumos.backend.ecf.firma.XmlFirmador;
import com.agroinsumos.backend.ecf.secuencia.EcfSecuenciaService;
import com.agroinsumos.backend.ecf.xml.EcfXmlBuilder;
import com.agroinsumos.backend.ecf.xml.EcfXsdValidator;
import com.agroinsumos.backend.ecf.xml.RfceXmlBuilder;
import com.agroinsumos.backend.ecf.xml.XmlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Emisión de e-CF: valida reglas fiscales, reserva e-NCF, genera XML, firma y guarda.
 * Todo en una transacción: si cualquier paso falla, el e-NCF no se consume.
 * El envío a la DGII ocurre después (EcfEnvioService), fuera de la transacción.
 */
@Service
@RequiredArgsConstructor
public class EcfEmisionService {

    private final EcfSecuenciaService secuencias;
    private final CompaniaRepository companias;
    private final EcfDocumentoRepository documentos;
    private final EcfXmlBuilder ecfBuilder;
    private final RfceXmlBuilder rfceBuilder;
    private final XmlFirmador firmador;
    private final CertificadoProvider certificados;
    private final EcfXsdValidator xsd;
    private final EcfProperties props;
    private final Clock clock;

    @Transactional
    public EcfResponse emitir(Long companiaId, EmitirEcfRequest req) {
        TipoEcf tipo = tipoValido(req.tipoEcf());
        var totales = calcular(req);
        validarReglas(tipo, req, totales);

        var emisor = companias.findById(companiaId).orElseThrow(() -> new NotFoundException("Compañía", companiaId));
        if (emisor.getRnc() == null || !emisor.getRnc().matches("\\d{9}|\\d{11}")) {
            throw new BusinessException("RNC_EMISOR_INVALIDO", "La compañía no tiene un RNC válido configurado");
        }
        var cred = certificados.obtener(companiaId); // falla antes de consumir secuencia si no hay certificado
        var reserva = secuencias.reservar(companiaId, tipo);
        LocalDate fechaEmision = req.fechaEmision() != null ? req.fechaEmision() : LocalDate.now(clock);
        LocalDateTime fechaFirma = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        var xml = ecfBuilder.construir(new EcfXmlBuilder.Datos(tipo, reserva.encf(), reserva.fechaVencimiento(),
                fechaEmision, emisor, req, totales, fechaFirma));
        var firmado = firmador.firmar(xml, cred);
        xsd.validarEcf(firmado.documento(), tipo);

        var d = new EcfDocumento();
        d.setCompaniaId(companiaId);
        d.setTipoEcf((short) tipo.codigo());
        d.setEncf(reserva.encf());
        d.setFechaEmision(fechaEmision);
        if (req.comprador() != null) {
            d.setRncComprador(req.comprador().rnc());
            d.setRazonSocialComprador(req.comprador().razonSocial());
        }
        d.setMontoGravado(totales.montoGravadoTotal());
        d.setMontoExento(totales.montoExento());
        d.setTotalItbis(totales.totalItbis());
        d.setMontoTotal(totales.montoTotal());
        d.setEncfModificado(req.referencia() == null ? null : req.referencia().ncfModificado());
        d.setOrigenTipo(req.origenTipo());
        d.setOrigenId(req.origenId());
        d.setCodigoSeguridad(firmado.codigoSeguridad());
        d.setFechaHoraFirma(fechaFirma);
        d.setXmlFirmado(XmlUtil.serializar(firmado.documento()));

        boolean resumen = tipo == TipoEcf.FACTURA_CONSUMO && totales.montoTotal().compareTo(props.umbralConsumoCompleto()) < 0;
        d.setEsResumenConsumo(resumen);
        if (resumen) {
            var rfce = firmador.firmar(rfceBuilder.construir(firmado.documento(), firmado.codigoSeguridad()), cred);
            xsd.validarRfce(rfce.documento());
            d.setXmlRfceFirmado(XmlUtil.serializar(rfce.documento()));
        }
        d.setEstado(EstadoEcf.FIRMADO);
        return EcfResponse.de(documentos.save(d));
    }

    private static TipoEcf tipoValido(int codigo) {
        TipoEcf tipo;
        try {
            tipo = TipoEcf.desdeCodigo(codigo);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("TIPO_ECF_INVALIDO", e.getMessage());
        }
        if (!tipo.soportado()) {
            throw new BusinessException("TIPO_ECF_NO_SOPORTADO", "El tipo " + codigo + " (" + tipo.descripcion()
                    + ") aún no está implementado");
        }
        return tipo;
    }

    private static EcfCalculadora.Totales calcular(EmitirEcfRequest req) {
        try {
            return EcfCalculadora.calcular(req.items());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("ITEM_INVALIDO", e.getMessage());
        }
    }

    private void validarReglas(TipoEcf tipo, EmitirEcfRequest req, EcfCalculadora.Totales t) {
        if (!tipo.esDeAdquisicion() && req.tipoIngresos() == null) {
            throw new BusinessException("TIPO_INGRESOS_REQUERIDO", "tipoIngresos es obligatorio para el e-CF " + tipo.codigo());
        }
        boolean tieneRnc = req.comprador() != null && req.comprador().rnc() != null;
        if (tipo.requiereRncComprador() && !tieneRnc) {
            throw new BusinessException("RNC_COMPRADOR_REQUERIDO", "El tipo " + tipo.codigo() + " requiere RNC/cédula del comprador");
        }
        if (tipo == TipoEcf.FACTURA_CONSUMO && !tieneRnc && t.montoTotal().compareTo(props.umbralConsumoCompleto()) >= 0) {
            throw new BusinessException("RNC_COMPRADOR_REQUERIDO",
                    "Facturas de consumo desde RD$" + props.umbralConsumoCompleto() + " requieren identificar al comprador");
        }
        if (tieneRnc && (req.comprador().razonSocial() == null || req.comprador().razonSocial().isBlank())) {
            throw new BusinessException("RAZON_SOCIAL_REQUERIDA", "Indique la razón social del comprador");
        }
        if (tipo.esNota() && req.referencia() == null) {
            throw new BusinessException("REFERENCIA_REQUERIDA", "Las notas de débito/crédito requieren el comprobante modificado");
        }
        if (req.tipoPago() == 2 && req.fechaLimitePago() == null) {
            throw new BusinessException("FECHA_LIMITE_REQUERIDA", "Las ventas a crédito requieren fechaLimitePago");
        }
        if (tipo == TipoEcf.GASTOS_MENORES) {
            if (req.comprador() != null && (req.comprador().rnc() != null || req.comprador().razonSocial() != null)) {
                throw new BusinessException("COMPRADOR_NO_APLICA", "El e-CF 43 (gastos menores) no lleva datos de comprador/vendedor");
            }
            // REVISAR con el contador: los gastos menores se emiten sin ITBIS (proveedores informales)
            if (t.montoGravadoTotal().signum() > 0) {
                throw new BusinessException("ITBIS_NO_APLICA", "El e-CF 43 (gastos menores) no admite ITBIS: use indicador 4 (exento)");
            }
        }
        if (t.tieneRetenciones()) {
            if (tipo != TipoEcf.COMPRAS) {
                throw new BusinessException("RETENCION_NO_APLICA", "Las retenciones por línea solo aplican al e-CF 41");
            }
            if (t.totalItbisRetenido().compareTo(t.totalItbis()) > 0) {
                throw new BusinessException("RETENCION_INVALIDA", "El ITBIS retenido no puede superar el ITBIS facturado");
            }
            if (t.totalIsrRetenido().compareTo(t.montoGravadoTotal().add(t.montoExento())) > 0) {
                throw new BusinessException("RETENCION_INVALIDA", "El ISR retenido no puede superar el monto facturado");
            }
        }
        if (t.montoTotal().signum() <= 0 && req.tipoPago() != 3) {
            throw new BusinessException("MONTO_INVALIDO", "El monto total debe ser mayor que cero");
        }
    }
}
