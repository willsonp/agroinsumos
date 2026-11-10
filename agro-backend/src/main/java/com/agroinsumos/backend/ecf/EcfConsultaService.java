package com.agroinsumos.backend.ecf;

import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.ecf.dto.EcfDtos.EcfResponse;
import com.agroinsumos.backend.ecf.dto.EcfDtos.TimbreResponse;
import com.agroinsumos.backend.ecf.xml.XmlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EcfConsultaService {

    private final EcfDocumentoRepository documentos;
    private final EcfProperties props;
    private final JdbcTemplate jdbc;

    public Page<EcfResponse> listar(Long companiaId, EstadoEcf estado, Pageable pageable) {
        var page = estado == null ? documentos.findByCompaniaId(companiaId, pageable)
                : documentos.findByCompaniaIdAndEstado(companiaId, estado, pageable);
        return page.map(EcfResponse::de);
    }

    public EcfResponse obtener(Long companiaId, Long id) {
        return EcfResponse.de(cargar(companiaId, id));
    }

    public String xml(Long companiaId, Long id, boolean rfce) {
        var d = cargar(companiaId, id);
        if (rfce && d.getXmlRfceFirmado() == null) throw new NotFoundException("RFCE del e-CF", id);
        return rfce ? d.getXmlRfceFirmado() : d.getXmlFirmado();
    }

    /**
     * URL del código QR (timbre) que debe imprimirse en la representación impresa del e-CF.
     * El frontend o el reporte (Jasper) convierte esta URL en QR.
     */
    public TimbreResponse timbre(Long companiaId, Long id) {
        var d = cargar(companiaId, id);
        String rncEmisor = jdbc.queryForObject("select rnc from compania where id = ?", String.class, companiaId);
        UriComponentsBuilder b;
        if (d.isEsResumenConsumo()) {
            b = UriComponentsBuilder.fromUriString(props.baseFc() + "/consultatimbrefc")
                    .queryParam("RncEmisor", rncEmisor)
                    .queryParam("ENCF", d.getEncf())
                    .queryParam("MontoTotal", XmlUtil.monto(d.getMontoTotal()))
                    .queryParam("CodigoSeguridad", d.getCodigoSeguridad());
        } else {
            b = UriComponentsBuilder.fromUriString(props.baseEcf() + "/consultatimbre")
                    .queryParam("RncEmisor", rncEmisor);
            if (d.getRncComprador() != null) b.queryParam("RncComprador", d.getRncComprador());
            b.queryParam("ENCF", d.getEncf())
                    .queryParam("FechaEmision", d.getFechaEmision().format(XmlUtil.FECHA))
                    .queryParam("MontoTotal", XmlUtil.monto(d.getMontoTotal()))
                    .queryParam("FechaFirma", d.getFechaHoraFirma().format(XmlUtil.FECHA_HORA))
                    .queryParam("CodigoSeguridad", d.getCodigoSeguridad());
        }
        return new TimbreResponse(d.getEncf(), d.getCodigoSeguridad(), b.encode().build().toUriString());
    }

    private EcfDocumento cargar(Long companiaId, Long id) {
        return documentos.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("e-CF", id));
    }
}
