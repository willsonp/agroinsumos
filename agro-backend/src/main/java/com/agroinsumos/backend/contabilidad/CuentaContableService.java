package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Catálogo de cuentas (legacy catalogo / controlcuenta). Las cuentas de grupo acumulan; solo las de detalle reciben movimientos. */
@Service
@RequiredArgsConstructor
public class CuentaContableService extends CrudService<CuentaContable, CuentaContableService.Request, CuentaContableService.Response> {

    public record Request(@NotBlank @Size(max = 14) @Pattern(regexp = "[0-9A-Za-z.-]+") String codigo,
                          @NotBlank @Size(max = 100) String nombre, @NotNull TipoCuenta tipo,
                          @Size(max = 14) String padreCodigo, Boolean aceptaMovimiento) {}

    public record Response(Long id, String codigo, String nombre, TipoCuenta tipo, Long padreId, String padreCodigo,
                           int nivel, boolean aceptaMovimiento, String estado) {}

    private final CuentaContableRepository repo;
    private final JdbcTemplate jdbc;

    @Override protected JpaRepository<CuentaContable, Long> repo() { return repo; }
    @Override protected String recurso() { return "Cuenta contable"; }
    @Override protected Page<CuentaContable> buscar(Long cia, String q, Pageable p) { return repo.buscar(cia, q, p); }

    @Override
    protected CuentaContable nueva(Long companiaId) {
        var c = new CuentaContable();
        c.setCompaniaId(companiaId);
        return c;
    }

    @Override
    protected void validar(CuentaContable c, Request r, Long cia) {
        String codigo = r.codigo().trim();
        repo.findByCompaniaIdAndCodigo(cia, codigo).filter(o -> !o.getId().equals(c.getId())).ifPresent(o -> {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe la cuenta " + codigo);
        });
        if (c.getId() != null && !codigo.equals(c.getCodigo()) && tieneMovimientos(c.getId())) {
            throw new BusinessException("CUENTA_CON_MOVIMIENTOS", "No se puede cambiar el código de una cuenta con movimientos");
        }
        boolean acepta = r.aceptaMovimiento() == null || r.aceptaMovimiento();
        if (c.getId() != null && !acepta && tieneMovimientos(c.getId())) {
            throw new BusinessException("CUENTA_CON_MOVIMIENTOS", "La cuenta tiene movimientos; no puede convertirse en cuenta de grupo");
        }
        if (c.getId() != null && acepta && repo.existsByPadreIdAndEstado(c.getId(), "A")) {
            throw new BusinessException("CUENTA_CON_SUBCUENTAS", "La cuenta tiene subcuentas; debe ser de grupo (sin movimientos)");
        }
        if (r.padreCodigo() != null && !r.padreCodigo().isBlank()) {
            var padre = repo.findByCompaniaIdAndCodigo(cia, r.padreCodigo().trim())
                    .orElseThrow(() -> new BusinessException("PADRE_INEXISTENTE", "No existe la cuenta de grupo " + r.padreCodigo()));
            if (padre.getId().equals(c.getId())) throw new BusinessException("PADRE_INVALIDO", "Una cuenta no puede ser su propio grupo");
            if (padre.getTipo() != r.tipo()) {
                throw new BusinessException("TIPO_DISTINTO_AL_GRUPO", "La cuenta debe ser del mismo tipo que su grupo (" + padre.getTipo() + ")");
            }
            if (padre.isAceptaMovimiento()) {
                if (tieneMovimientos(padre.getId())) {
                    throw new BusinessException("PADRE_CON_MOVIMIENTOS", "La cuenta " + padre.getCodigo() + " tiene movimientos y no puede tener subcuentas");
                }
                padre.setAceptaMovimiento(false);
            }
        }
    }

    @Override
    protected void aplicar(CuentaContable c, Request r, Long cia) {
        c.setCodigo(r.codigo().trim());
        c.setNombre(r.nombre().trim());
        c.setTipo(r.tipo());
        c.setAceptaMovimiento(r.aceptaMovimiento() == null || r.aceptaMovimiento());
        if (r.padreCodigo() == null || r.padreCodigo().isBlank()) {
            c.setPadreId(null);
            c.setNivel(1);
        } else {
            var padre = repo.findByCompaniaIdAndCodigo(cia, r.padreCodigo().trim()).orElseThrow();
            c.setPadreId(padre.getId());
            c.setNivel(Math.min(10, padre.getNivel() + 1));
        }
    }

    @Override
    @Transactional
    public void desactivar(Long cia, Long id) {
        var c = cargar(cia, id);
        if (repo.existsByPadreIdAndEstado(id, "A")) throw new BusinessException("CUENTA_CON_SUBCUENTAS", "Desactive primero sus subcuentas");
        c.setEstado("I");
    }

    @Override
    protected Response respuesta(CuentaContable c) {
        String padre = c.getPadreId() == null ? null : repo.findById(c.getPadreId()).map(CuentaContable::getCodigo).orElse(null);
        return new Response(c.getId(), c.getCodigo(), c.getNombre(), c.getTipo(), c.getPadreId(), padre, c.getNivel(),
                c.isAceptaMovimiento(), c.getEstado());
    }

    /** Todo el catálogo ordenado por código (árbol para pantallas y reportes). */
    @Transactional(readOnly = true)
    public List<Response> arbol(Long cia) {
        var todas = repo.findByCompaniaIdOrderByCodigo(cia);
        Map<Long, String> codigos = new HashMap<>();
        todas.forEach(c -> codigos.put(c.getId(), c.getCodigo()));
        return todas.stream().map(c -> new Response(c.getId(), c.getCodigo(), c.getNombre(), c.getTipo(), c.getPadreId(),
                codigos.get(c.getPadreId()), c.getNivel(), c.isAceptaMovimiento(), c.getEstado())).toList();
    }

    private boolean tieneMovimientos(Long cuentaId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from asiento_linea where cuenta_id = ?)", Boolean.class, cuentaId));
    }

    // ------------------------------------------------------------------ catálogo base
    private record Plantilla(String codigo, String nombre, TipoCuenta tipo, String padre, ClaveContable... claves) {}

    private static final List<Plantilla> PLANTILLA = List.of(
            new Plantilla("1", "ACTIVOS", TipoCuenta.ACTIVO, null),
            new Plantilla("11", "Activos corrientes", TipoCuenta.ACTIVO, "1"),
            new Plantilla("1101", "Efectivo en caja y bancos", TipoCuenta.ACTIVO, "11"),
            new Plantilla("110101", "Caja general", TipoCuenta.ACTIVO, "1101", ClaveContable.CAJA),
            new Plantilla("110102", "Bancos", TipoCuenta.ACTIVO, "1101", ClaveContable.BANCO),
            new Plantilla("1102", "Cuentas por cobrar", TipoCuenta.ACTIVO, "11"),
            new Plantilla("110201", "Cuentas por cobrar clientes", TipoCuenta.ACTIVO, "1102", ClaveContable.CXC_CLIENTES),
            new Plantilla("110202", "Tarjetas de crédito por cobrar", TipoCuenta.ACTIVO, "1102", ClaveContable.TARJETAS_POR_COBRAR),
            new Plantilla("1103", "Inventarios", TipoCuenta.ACTIVO, "11"),
            new Plantilla("110301", "Inventario de mercancías", TipoCuenta.ACTIVO, "1103", ClaveContable.INVENTARIO),
            new Plantilla("1104", "Impuestos adelantados", TipoCuenta.ACTIVO, "11"),
            new Plantilla("110401", "ITBIS adelantado en compras", TipoCuenta.ACTIVO, "1104", ClaveContable.ITBIS_ADELANTADO),
            new Plantilla("110402", "ITBIS retenido por clientes", TipoCuenta.ACTIVO, "1104", ClaveContable.ITBIS_RETENIDO_POR_CLIENTES),
            new Plantilla("110403", "ISR retenido por clientes", TipoCuenta.ACTIVO, "1104", ClaveContable.ISR_RETENIDO_POR_CLIENTES),
            new Plantilla("12", "Activos no corrientes", TipoCuenta.ACTIVO, "1"),
            new Plantilla("1201", "Mobiliario y equipos", TipoCuenta.ACTIVO, "12"),
            new Plantilla("2", "PASIVOS", TipoCuenta.PASIVO, null),
            new Plantilla("21", "Pasivos corrientes", TipoCuenta.PASIVO, "2"),
            new Plantilla("2101", "Cuentas por pagar", TipoCuenta.PASIVO, "21"),
            new Plantilla("210101", "Cuentas por pagar proveedores", TipoCuenta.PASIVO, "2101", ClaveContable.CXP_PROVEEDORES),
            new Plantilla("2102", "Impuestos por pagar", TipoCuenta.PASIVO, "21"),
            new Plantilla("210201", "ITBIS por pagar", TipoCuenta.PASIVO, "2102", ClaveContable.ITBIS_POR_PAGAR),
            new Plantilla("210202", "ITBIS retenido a terceros por pagar", TipoCuenta.PASIVO, "2102", ClaveContable.ITBIS_RETENIDO_POR_PAGAR),
            new Plantilla("210203", "ISR retenido a terceros por pagar", TipoCuenta.PASIVO, "2102", ClaveContable.ISR_RETENIDO_POR_PAGAR),
            new Plantilla("3", "CAPITAL", TipoCuenta.CAPITAL, null),
            new Plantilla("3101", "Capital suscrito y pagado", TipoCuenta.CAPITAL, "3"),
            new Plantilla("3102", "Resultados acumulados", TipoCuenta.CAPITAL, "3", ClaveContable.RESULTADOS_ACUMULADOS),
            new Plantilla("4", "INGRESOS", TipoCuenta.INGRESO, null),
            new Plantilla("41", "Ingresos operacionales", TipoCuenta.INGRESO, "4"),
            new Plantilla("4101", "Ventas de mercancías", TipoCuenta.INGRESO, "41", ClaveContable.INGRESOS_VENTAS),
            new Plantilla("4102", "Devoluciones en ventas", TipoCuenta.INGRESO, "41", ClaveContable.DEVOLUCIONES_VENTAS),
            new Plantilla("4103", "Descuentos en ventas", TipoCuenta.INGRESO, "41", ClaveContable.DESCUENTOS_VENTAS),
            new Plantilla("42", "Otros ingresos", TipoCuenta.INGRESO, "4"),
            new Plantilla("4201", "Otros ingresos", TipoCuenta.INGRESO, "42", ClaveContable.OTROS_INGRESOS),
            new Plantilla("4202", "Ingresos financieros", TipoCuenta.INGRESO, "42", ClaveContable.INGRESOS_FINANCIEROS),
            new Plantilla("4203", "Sobrantes de caja", TipoCuenta.INGRESO, "42", ClaveContable.SOBRANTES_CAJA),
            new Plantilla("4204", "Ganancia cambiaria", TipoCuenta.INGRESO, "42", ClaveContable.GANANCIA_CAMBIARIA),
            new Plantilla("5", "COSTOS", TipoCuenta.COSTO, null),
            new Plantilla("5101", "Costo de mercancías vendidas", TipoCuenta.COSTO, "5", ClaveContable.COSTO_VENTAS),
            new Plantilla("6", "GASTOS", TipoCuenta.GASTO, null),
            new Plantilla("6101", "Gastos generales", TipoCuenta.GASTO, "6", ClaveContable.GASTOS_CAJA, ClaveContable.GASTOS_COMPRAS),
            new Plantilla("6102", "Gastos bancarios", TipoCuenta.GASTO, "6", ClaveContable.GASTOS_BANCARIOS),
            new Plantilla("6103", "Faltantes de caja", TipoCuenta.GASTO, "6", ClaveContable.FALTANTES_CAJA),
            new Plantilla("6104", "Pérdida cambiaria", TipoCuenta.GASTO, "6", ClaveContable.PERDIDA_CAMBIARIA));

    /**
     * Carga un catálogo base (estructura usual en RD) y la configuración de cuentas por defecto.
     * Solo si la compañía aún no tiene cuentas. Devuelve la cantidad de cuentas creadas.
     */
    @Transactional
    public int cargarPlantilla(Long cia) {
        if (repo.countByCompaniaId(cia) > 0) {
            throw new BusinessException("CATALOGO_EXISTENTE", "La compañía ya tiene catálogo de cuentas");
        }
        Map<String, CuentaContable> creadas = new HashMap<>();
        Set<String> padres = new HashSet<>();
        PLANTILLA.forEach(p -> { if (p.padre() != null) padres.add(p.padre()); });
        for (var p : PLANTILLA) {
            var c = nueva(cia);
            c.setCodigo(p.codigo());
            c.setNombre(p.nombre());
            c.setTipo(p.tipo());
            c.setAceptaMovimiento(!padres.contains(p.codigo()));
            var padre = p.padre() == null ? null : creadas.get(p.padre());
            c.setPadreId(padre == null ? null : padre.getId());
            c.setNivel(padre == null ? 1 : padre.getNivel() + 1);
            creadas.put(p.codigo(), repo.save(c));
            for (var clave : p.claves()) {
                jdbc.update("""
                        insert into cuenta_contable_config (compania_id, clave, codigo) values (?, ?, ?)
                        on conflict (compania_id, clave) do nothing""", cia, clave.name(), p.codigo());
            }
        }
        return PLANTILLA.size();
    }
}
