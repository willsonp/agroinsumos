package com.agroinsumos.backend.catalogo.zona;

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
public class ZonaService extends CrudService<Zona, ZonaService.Request, ZonaService.Response> {

    public record Request(@NotBlank @Size(max = 60) String nombre) {}

    public record Response(Long id, String nombre, String estado) {}

    private final ZonaRepository repo;

    @Override protected JpaRepository<Zona, Long> repo() { return repo; }
    @Override protected String recurso() { return "Zona"; }
    @Override protected Zona nueva(Long companiaId) { return new Zona(); }
    @Override protected Page<Zona> buscar(Long companiaId, String q, Pageable p) { return repo.buscar(q, p); }

    @Override
    protected void validar(Zona e, Request r, Long companiaId) {
        boolean cambia = e.getNombre() == null || !e.getNombre().equalsIgnoreCase(r.nombre().trim());
        if (cambia && repo.existsByNombreIgnoreCase(r.nombre().trim())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe un registro con ese nombre");
        }
    }

    @Override
    protected void aplicar(Zona e, Request r, Long companiaId) {
        e.setNombre(r.nombre().trim());

    }

    @Override
    protected Response respuesta(Zona e) { return new Response(e.getId(), e.getNombre(), e.getEstado()); }
}
