package com.agroinsumos.backend.banco;

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

/** Catálogo de bancos (legacy banco / FrmBancos). */
@Service
@RequiredArgsConstructor
public class BancoService extends CrudService<Banco, BancoService.Request, BancoService.Response> {

    public record Request(@NotBlank @Size(max = 60) String nombre) {}

    public record Response(Long id, String nombre, String estado) {}

    private final BancoRepository repo;

    @Override protected JpaRepository<Banco, Long> repo() { return repo; }
    @Override protected String recurso() { return "Banco"; }
    @Override protected Banco nueva(Long companiaId) { return new Banco(); }
    @Override protected Page<Banco> buscar(Long companiaId, String q, Pageable p) { return repo.buscar(q, p); }

    @Override
    protected void validar(Banco b, Request r, Long companiaId) {
        boolean cambia = b.getNombre() == null || !b.getNombre().equalsIgnoreCase(r.nombre().trim());
        if (cambia && repo.existsByNombreIgnoreCase(r.nombre().trim())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe el banco " + r.nombre());
        }
    }

    @Override protected void aplicar(Banco b, Request r, Long companiaId) { b.setNombre(r.nombre().trim().toUpperCase()); }

    @Override protected Response respuesta(Banco b) { return new Response(b.getId(), b.getNombre(), b.getEstado()); }
}
