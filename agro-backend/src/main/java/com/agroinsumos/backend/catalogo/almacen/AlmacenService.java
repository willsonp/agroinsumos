package com.agroinsumos.backend.catalogo.almacen;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlmacenService extends CrudService<Almacen, AlmacenService.Request, AlmacenService.Response> {

    public record Request(@NotBlank @Size(max = 45) String nombre, @Size(max = 100) String ubicacion, Boolean principal) {}

    public record Response(Long id, String nombre, String ubicacion, boolean principal, String estado) {}

    private final AlmacenRepository repo;

    @Override protected JpaRepository<Almacen, Long> repo() { return repo; }
    @Override protected String recurso() { return "Almacén"; }
    @Override protected Page<Almacen> buscar(Long c, String q, Pageable p) { return repo.buscar(c, q, p); }

    @Override
    protected Almacen nueva(Long companiaId) {
        var a = new Almacen();
        a.setCompaniaId(companiaId);
        return a;
    }

    @Override
    protected void aplicar(Almacen a, Request r, Long companiaId) {
        a.setNombre(r.nombre().trim());
        a.setUbicacion(r.ubicacion());
        boolean principal = Boolean.TRUE.equals(r.principal());
        if (principal && !a.isPrincipal()) {
            repo.findByCompaniaIdAndPrincipalTrue(companiaId).ifPresent(prev -> {
                prev.setPrincipal(false);
                repo.flush();
            });
        }
        a.setPrincipal(principal);
    }

    @Override
    @Transactional
    public void desactivar(Long companiaId, Long id) {
        if (cargar(companiaId, id).isPrincipal()) {
            throw new BusinessException("ALMACEN_PRINCIPAL", "No se puede desactivar el almacén principal");
        }
        super.desactivar(companiaId, id);
    }

    @Override
    protected Response respuesta(Almacen a) {
        return new Response(a.getId(), a.getNombre(), a.getUbicacion(), a.isPrincipal(), a.getEstado());
    }

    /** Almacén indicado o, si es null, el principal de la compañía. */
    public Almacen resolver(Long companiaId, Long almacenId) {
        if (almacenId != null) return cargarActiva(companiaId, almacenId);
        return repo.findByCompaniaIdAndPrincipalTrue(companiaId)
                .orElseThrow(() -> new BusinessException("SIN_ALMACEN_PRINCIPAL",
                        "Configure un almacén principal o indique el almacén"));
    }
}
