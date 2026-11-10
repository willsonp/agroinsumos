package com.agroinsumos.backend.catalogo.cliente;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.crud.Rnc;
import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/** Reemplaza Ctrl_Cliente / FrmClientes (procs. Agrega_Cliente, Edita_Cliente, Elimina_Cliente). */
@Service
@RequiredArgsConstructor
public class ClienteService extends CrudService<Cliente, ClienteService.Request, ClienteService.Response> {

    private static final Set<Integer> TIPOS_ECF = Set.of(31, 32, 44, 45);

    public record Request(@NotBlank @Size(max = 150) String nombre, String rncCedula,
                          @NotNull Integer tipoEcfPreferido, Boolean exentoItbis, @NotNull TipoPrecio tipoPrecio,
                          @NotNull @DecimalMin("0") BigDecimal limiteCredito, @Min(0) @Max(365) Integer diasCredito,
                          Long ciudadId, Long zonaId, @Size(max = 150) String direccion, @Email String correo,
                          @Size(max = 20) String telefono, @Size(max = 20) String celular,
                          @Size(max = 100) String contacto, @Size(max = 12) String cuentaContable) {}

    public record Response(Long id, String nombre, String rncCedula, int tipoEcfPreferido, boolean exentoItbis,
                           TipoPrecio tipoPrecio, BigDecimal limiteCredito, int diasCredito, Long ciudadId,
                           Long zonaId, String direccion, String correo, String telefono, String celular,
                           String contacto, String cuentaContable, BigDecimal saldoAFavor, String estado) {}

    private final ClienteRepository repo;

    @Override protected JpaRepository<Cliente, Long> repo() { return repo; }
    @Override protected String recurso() { return "Cliente"; }
    @Override protected Page<Cliente> buscar(Long c, String q, Pageable p) { return repo.buscar(c, q, p); }

    @Override
    protected Cliente nueva(Long companiaId) {
        var c = new Cliente();
        c.setCompaniaId(companiaId);
        return c;
    }

    @Override
    protected void validar(Cliente c, Request r, Long companiaId) {
        String rnc = Rnc.normalizar(r.rncCedula());
        if (!TIPOS_ECF.contains(r.tipoEcfPreferido())) {
            throw new BusinessException("TIPO_ECF_INVALIDO", "Tipo de comprobante para cliente: 31, 32, 44 o 45");
        }
        if (r.tipoEcfPreferido() != 32 && rnc == null) {
            throw new BusinessException("RNC_REQUERIDO", "Para comprobante " + r.tipoEcfPreferido() + " el cliente debe tener RNC/cédula");
        }
        if (rnc != null && !Objects.equals(rnc, c.getRncCedula()) && repo.existsByCompaniaIdAndRncCedula(companiaId, rnc)) {
            throw new BusinessException(HttpStatus.CONFLICT, "RNC_DUPLICADO", "Ya existe un cliente con el RNC/cédula " + rnc);
        }
    }

    @Override
    protected void aplicar(Cliente c, Request r, Long companiaId) {
        c.setNombre(r.nombre().trim().toUpperCase());
        c.setRncCedula(Rnc.normalizar(r.rncCedula()));
        c.setTipoEcfPreferido(r.tipoEcfPreferido().shortValue());
        c.setExentoItbis(Boolean.TRUE.equals(r.exentoItbis()));
        c.setTipoPrecio(r.tipoPrecio());
        c.setLimiteCredito(r.limiteCredito());
        c.setDiasCredito(r.diasCredito() == null ? 0 : r.diasCredito());
        c.setCiudadId(r.ciudadId());
        c.setZonaId(r.zonaId());
        c.setDireccion(r.direccion());
        c.setCorreo(r.correo());
        c.setTelefono(r.telefono());
        c.setCelular(r.celular());
        c.setContacto(r.contacto());
        c.setCuentaContable(r.cuentaContable());
    }

    @Override
    protected Response respuesta(Cliente c) {
        return new Response(c.getId(), c.getNombre(), c.getRncCedula(), c.getTipoEcfPreferido(), c.isExentoItbis(),
                c.getTipoPrecio(), c.getLimiteCredito(), c.getDiasCredito(), c.getCiudadId(), c.getZonaId(),
                c.getDireccion(), c.getCorreo(), c.getTelefono(), c.getCelular(), c.getContacto(),
                c.getCuentaContable(), c.getSaldoAFavor(), c.getEstado());
    }

    public BigDecimal balancePendiente(Long clienteId) {
        return repo.balancePendiente(clienteId);
    }
}
