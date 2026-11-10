package com.agroinsumos.backend.catalogo.departamento;

import com.agroinsumos.backend.common.crud.CrudService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DepartamentoService extends CrudService<Departamento, DepartamentoService.Request, DepartamentoService.Response> {

    public record Request(@NotBlank @Size(max = 45) String nombre, @NotNull Departamento.Tipo tipo,
                          @Size(max = 12) String cuentaCosto, @Size(max = 12) String cuentaIngreso,
                          @Size(max = 12) String cuentaInventario) {}

    public record Response(Long id, String nombre, Departamento.Tipo tipo, String cuentaCosto, String cuentaIngreso,
                           String cuentaInventario, String estado) {}

    private final DepartamentoRepository repo;

    @Override protected JpaRepository<Departamento, Long> repo() { return repo; }
    @Override protected String recurso() { return "Departamento"; }
    @Override protected Page<Departamento> buscar(Long c, String q, Pageable p) { return repo.buscar(c, q, p); }

    @Override
    protected Departamento nueva(Long companiaId) {
        var d = new Departamento();
        d.setCompaniaId(companiaId);
        return d;
    }

    @Override
    protected void aplicar(Departamento d, Request r, Long companiaId) {
        d.setNombre(r.nombre().trim());
        d.setTipo(r.tipo());
        d.setCuentaCosto(r.cuentaCosto());
        d.setCuentaIngreso(r.cuentaIngreso());
        d.setCuentaInventario(r.cuentaInventario());
    }

    @Override
    protected Response respuesta(Departamento d) {
        return new Response(d.getId(), d.getNombre(), d.getTipo(), d.getCuentaCosto(), d.getCuentaIngreso(),
                d.getCuentaInventario(), d.getEstado());
    }
}
