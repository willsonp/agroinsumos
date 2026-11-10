package com.agroinsumos.backend.catalogo.vendedor;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.crud.Rnc;
import com.agroinsumos.backend.usuario.UsuarioCreadoEvent;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VendedorService extends CrudService<Vendedor, VendedorService.Request, VendedorService.Response> {

    public record Request(@NotBlank @Size(max = 90) String nombre, String rncCedula, @Past LocalDate fechaNacimiento,
                          @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal comision, Long ciudadId,
                          @Size(max = 150) String direccion, @Email String correo, @Size(max = 20) String telefono) {}

    public record Response(Long id, Long usuarioId, String nombre, String rncCedula, LocalDate fechaNacimiento,
                           BigDecimal comision, Long ciudadId, String direccion, String correo, String telefono,
                           String estado) {}

    private final VendedorRepository repo;

    @Override protected JpaRepository<Vendedor, Long> repo() { return repo; }
    @Override protected String recurso() { return "Vendedor"; }
    @Override protected Page<Vendedor> buscar(Long c, String q, Pageable p) { return repo.buscar(c, q, p); }

    @Override
    protected Vendedor nueva(Long companiaId) {
        var v = new Vendedor();
        v.setCompaniaId(companiaId);
        return v;
    }

    @Override
    protected void aplicar(Vendedor v, Request r, Long companiaId) {
        v.setNombre(r.nombre().trim());
        v.setRncCedula(Rnc.normalizar(r.rncCedula()));
        v.setFechaNacimiento(r.fechaNacimiento());
        v.setComision(r.comision());
        v.setCiudadId(r.ciudadId());
        v.setDireccion(r.direccion());
        v.setCorreo(r.correo());
        v.setTelefono(r.telefono());
    }

    @Override
    protected Response respuesta(Vendedor v) {
        return new Response(v.getId(), v.getUsuarioId(), v.getNombre(), v.getRncCedula(), v.getFechaNacimiento(),
                v.getComision(), v.getCiudadId(), v.getDireccion(), v.getCorreo(), v.getTelefono(), v.getEstado());
    }

    public Optional<Vendedor> deUsuario(Long usuarioId) {
        return repo.findByUsuarioId(usuarioId).filter(v -> "A".equals(v.getEstado()));
    }

    /**
     * Equivalente a "call agrega_vendedor(...)" que hacía Ctrl_Usuario al guardar:
     * todo usuario es también vendedor. Ahora desacoplado mediante evento.
     */
    @EventListener
    @Transactional
    public void alGuardarUsuario(UsuarioCreadoEvent ev) {
        var v = repo.findByUsuarioId(ev.usuarioId()).orElseGet(() -> {
            var nuevo = nueva(ev.companiaId());
            nuevo.setUsuarioId(ev.usuarioId());
            return nuevo;
        });
        v.setNombre(ev.nombre());
        v.setRncCedula(ev.cedula());
        v.setDireccion(ev.direccion());
        v.setCorreo(ev.correo());
        repo.save(v);
    }
}
