package com.agroinsumos.backend.catalogo.unidad;

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
public class UnidadService extends CrudService<Unidad, UnidadService.Request, UnidadService.Response> {

    public record Request(@NotBlank @Size(max = 60) String nombre, @Size(max = 10) String abreviatura) {}

    public record Response(Long id, String nombre, String abreviatura, String estado) {}

    private final UnidadRepository repo;

    @Override protected JpaRepository<Unidad, Long> repo() { return repo; }
    @Override protected String recurso() { return "Unidad"; }
    @Override protected Unidad nueva(Long companiaId) { return new Unidad(); }
    @Override protected Page<Unidad> buscar(Long companiaId, String q, Pageable p) { return repo.buscar(q, p); }

    @Override
    protected void validar(Unidad e, Request r, Long companiaId) {
        boolean cambia = e.getNombre() == null || !e.getNombre().equalsIgnoreCase(r.nombre().trim());
        if (cambia && repo.existsByNombreIgnoreCase(r.nombre().trim())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe un registro con ese nombre");
        }
    }

    @Override
    protected void aplicar(Unidad e, Request r, Long companiaId) {
        e.setNombre(r.nombre().trim());
        e.setAbreviatura(r.abreviatura());
    }

    @Override
    protected Response respuesta(Unidad e) { return new Response(e.getId(), e.getNombre(), e.getAbreviatura(), e.getEstado()); }
}
