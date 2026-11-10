package com.agroinsumos.backend.inventario.etiqueta;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ImpresoraEtiquetaService extends CrudService<ImpresoraEtiqueta, ImpresoraEtiquetaService.Request, ImpresoraEtiquetaService.Response> {

    /** Solo puertos RAW de impresora (9100-9109): el servidor no abre conexiones a otros servicios. */
    public record Request(@NotBlank @Size(max = 60) String nombre,
                          @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9.-]+", message = "IP o nombre de equipo") String host,
                          @Min(9100) @Max(9109) Integer puerto, @NotNull Integer dpi,
                          @NotNull @DecimalMin("10") @DecimalMax("120") BigDecimal anchoMm,
                          @NotNull @DecimalMin("10") @DecimalMax("200") BigDecimal altoMm) {}

    public record Response(Long id, String nombre, String host, int puerto, int dpi, BigDecimal anchoMm, BigDecimal altoMm, String estado) {}

    private final ImpresoraEtiquetaRepository repo;

    @Override protected JpaRepository<ImpresoraEtiqueta, Long> repo() { return repo; }
    @Override protected String recurso() { return "Impresora de etiquetas"; }
    @Override protected Page<ImpresoraEtiqueta> buscar(Long cia, String q, Pageable p) { return repo.buscar(cia, q, p); }

    @Override
    protected ImpresoraEtiqueta nueva(Long cia) {
        var i = new ImpresoraEtiqueta();
        i.setCompaniaId(cia);
        return i;
    }

    @Override
    protected void validar(ImpresoraEtiqueta i, Request r, Long cia) {
        if (r.dpi() != 203 && r.dpi() != 300 && r.dpi() != 600) throw new BusinessException("DPI_INVALIDO", "Resolución: 203, 300 o 600 dpi");
        boolean cambia = i.getNombre() == null || !i.getNombre().equalsIgnoreCase(r.nombre().trim());
        if (cambia && repo.existsByCompaniaIdAndNombreIgnoreCase(cia, r.nombre().trim())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe la impresora " + r.nombre());
        }
    }

    @Override
    protected void aplicar(ImpresoraEtiqueta i, Request r, Long cia) {
        i.setNombre(r.nombre().trim());
        i.setHost(r.host().trim());
        i.setPuerto(r.puerto() == null ? 9100 : r.puerto());
        i.setDpi(r.dpi());
        i.setAnchoMm(r.anchoMm());
        i.setAltoMm(r.altoMm());
    }

    @Override
    protected Response respuesta(ImpresoraEtiqueta i) {
        return new Response(i.getId(), i.getNombre(), i.getHost(), i.getPuerto(), i.getDpi(), i.getAnchoMm(), i.getAltoMm(), i.getEstado());
    }
}
