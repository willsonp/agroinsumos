package com.agroinsumos.backend.catalogo.proveedor;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.crud.Rnc;
import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProveedorService extends CrudService<Proveedor, ProveedorService.Request, ProveedorService.Response> {

    public record Request(@NotBlank @Size(max = 150) String nombre, String rncCedula, @Size(max = 255) String direccion,
                          @Size(max = 60) String sector, Long ciudadId, @Email String correo,
                          @Size(max = 20) String telefono, @Size(max = 100) String contacto,
                          @Size(max = 12) String cuentaContable) {}

    public record Response(Long id, String nombre, String rncCedula, String direccion, String sector, Long ciudadId,
                           String correo, String telefono, String contacto, String cuentaContable, String estado) {}

    private final ProveedorRepository repo;

    @Override protected JpaRepository<Proveedor, Long> repo() { return repo; }
    @Override protected String recurso() { return "Proveedor"; }
    @Override protected Page<Proveedor> buscar(Long c, String q, Pageable p) { return repo.buscar(c, q, p); }

    @Override
    protected Proveedor nueva(Long companiaId) {
        var p = new Proveedor();
        p.setCompaniaId(companiaId);
        return p;
    }

    @Override
    protected void validar(Proveedor p, Request r, Long companiaId) {
        String rnc = Rnc.normalizar(r.rncCedula());
        if (rnc != null && !Objects.equals(rnc, p.getRncCedula()) && repo.existsByCompaniaIdAndRncCedula(companiaId, rnc)) {
            throw new BusinessException(HttpStatus.CONFLICT, "RNC_DUPLICADO", "Ya existe un proveedor con el RNC/cédula " + rnc);
        }
    }

    @Override
    protected void aplicar(Proveedor p, Request r, Long companiaId) {
        p.setNombre(r.nombre().trim().toUpperCase());
        p.setRncCedula(Rnc.normalizar(r.rncCedula()));
        p.setDireccion(r.direccion());
        p.setSector(r.sector());
        p.setCiudadId(r.ciudadId());
        p.setCorreo(r.correo());
        p.setTelefono(r.telefono());
        p.setContacto(r.contacto());
        p.setCuentaContable(r.cuentaContable());
    }

    @Override
    protected Response respuesta(Proveedor p) {
        return new Response(p.getId(), p.getNombre(), p.getRncCedula(), p.getDireccion(), p.getSector(),
                p.getCiudadId(), p.getCorreo(), p.getTelefono(), p.getContacto(), p.getCuentaContable(), p.getEstado());
    }
}
