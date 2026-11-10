package com.agroinsumos.backend.catalogo.ciudad;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CiudadService extends CrudService<Ciudad, CiudadService.Request, CiudadService.Response> {

    public record Request(@NotBlank @Size(max = 60) String nombre, @Size(max = 60) String provincia) {}

    public record Response(Long id, String nombre, String provincia, String estado) {}

    private final CiudadRepository repo;

    @Override protected JpaRepository<Ciudad, Long> repo() { return repo; }
    @Override protected String recurso() { return "Ciudad"; }
    @Override protected Ciudad nueva(Long companiaId) { return new Ciudad(); }
    @Override protected Page<Ciudad> buscar(Long companiaId, String q, Pageable p) { return repo.buscar(q, p); }

    @Override
    protected void validar(Ciudad e, Request r, Long companiaId) {
        boolean cambia = e.getNombre() == null || !e.getNombre().equalsIgnoreCase(r.nombre().trim());
        if (cambia && repo.existsByNombreIgnoreCase(r.nombre().trim())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe un registro con ese nombre");
        }
    }

    @Override
    protected void aplicar(Ciudad e, Request r, Long companiaId) {
        e.setNombre(r.nombre().trim());
        e.setProvincia(r.provincia());
    }

    @Override
    protected Response respuesta(Ciudad e) { return new Response(e.getId(), e.getNombre(), e.getProvincia(), e.getEstado()); }
}
