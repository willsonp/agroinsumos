package com.agroinsumos.backend.inventario.articulo;

import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.catalogo.departamento.DepartamentoService;
import com.agroinsumos.backend.catalogo.proveedor.ProveedorService;
import com.agroinsumos.backend.catalogo.unidad.UnidadService;
import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.inventario.kardex.ExistenciaRepository;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/** Reemplaza Ctrl_Articulo / FrmArticulos. Precios siempre SIN ITBIS (el ITBIS se calcula al facturar). */
@Service
@RequiredArgsConstructor
public class ArticuloService extends CrudService<Articulo, ArticuloService.Request, ArticuloService.Response> {

    public record Request(
            @Size(max = 20) String codigoBarra, @Size(max = 20) String referencia,
            @NotBlank @Size(max = 80) String nombre, @Size(max = 500) String descripcion,
            @NotNull Articulo.Tipo tipo, @NotNull Long departamentoId, @NotNull Long unidadId, Long proveedorId,
            @NotNull @Min(1) @Max(4) Integer indicadorItbis,
            @NotNull @DecimalMin("0") BigDecimal costo,
            @NotNull @DecimalMin("0") BigDecimal precioContado,
            @DecimalMin("0") BigDecimal precioCredito, @DecimalMin("0") BigDecimal precioMayor,
            @DecimalMin("0") @DecimalMax("100") BigDecimal descuentoMaximo,
            @DecimalMin("0") BigDecimal stockMinimo, @Size(max = 50) String ubicacion, @Size(max = 255) String imagenUrl,
            /** true: los precios enviados incluyen ITBIS y se convierten a base (precio / (1 + tasa)). */
            Boolean preciosIncluyenItbis,
            /** Controla lotes y fechas de vencimiento. */
            Boolean manejaVencimiento,
            /** Presentación opcional (Saco, Caja): equivale a factorEmpaque unidades base. Precios vacíos = base × factor. */
            Long unidadEmpaqueId, @DecimalMin(value = "1", inclusive = false) BigDecimal factorEmpaque,
            @DecimalMin("0") BigDecimal precioEmpaqueContado, @DecimalMin("0") BigDecimal precioEmpaqueCredito,
            @DecimalMin("0") BigDecimal precioEmpaqueMayor, @Size(max = 20) String codigoBarraEmpaque) {}

    public record Response(Long id, String codigoBarra, String referencia, String nombre, String descripcion,
                           Articulo.Tipo tipo, Long departamentoId, Long unidadId, Long proveedorId, int indicadorItbis,
                           BigDecimal costo, BigDecimal costoPromedio, BigDecimal precioContado,
                           BigDecimal precioCredito, BigDecimal precioMayor, BigDecimal precioContadoConItbis,
                           BigDecimal precioCreditoConItbis, BigDecimal precioMayorConItbis, BigDecimal descuentoMaximo,
                           BigDecimal stockMinimo, BigDecimal existencia, String ubicacion, String imagenUrl,
                           String estado, boolean manejaVencimiento,
                           String unidad, Long unidadEmpaqueId, String unidadEmpaque, BigDecimal factorEmpaque,
                           BigDecimal precioEmpaqueContadoConItbis, BigDecimal precioEmpaqueCreditoConItbis,
                           BigDecimal precioEmpaqueMayorConItbis, String codigoBarraEmpaque) {}

    private final ArticuloRepository repo;
    private final ExistenciaRepository existencias;
    private final DepartamentoService departamentos;
    private final UnidadService unidades;
    private final ProveedorService proveedores;

    @Override protected JpaRepository<Articulo, Long> repo() { return repo; }
    @Override protected String recurso() { return "Artículo"; }
    @Override protected Page<Articulo> buscar(Long c, String q, Pageable p) { return repo.buscar(c, q, p); }

    public Page<Response> seleccionar(Long cia, String q, Long departamentoId, boolean conExistencia, Pageable p) {
        return repo.seleccionar(cia, q == null || q.isBlank() ? null : q.trim(), departamentoId, conExistencia, p).map(this::respuesta);
    }

    @Override
    protected Articulo nueva(Long companiaId) {
        var a = new Articulo();
        a.setCompaniaId(companiaId);
        return a;
    }

    @Override
    protected void validar(Articulo a, Request r, Long companiaId) {
        departamentos.cargarActiva(companiaId, r.departamentoId());
        unidades.cargarActiva(companiaId, r.unidadId());
        if (r.proveedorId() != null) proveedores.cargarActiva(companiaId, r.proveedorId());
        String cb = blank(r.codigoBarra());
        if (cb != null && !Objects.equals(cb, a.getCodigoBarra()) && repo.existsByCompaniaIdAndCodigoBarra(companiaId, cb)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CODIGO_BARRA_DUPLICADO", "Ya existe un artículo con el código " + cb);
        }
        if (r.unidadEmpaqueId() != null) {
            unidades.cargarActiva(companiaId, r.unidadEmpaqueId());
            if (r.factorEmpaque() == null) {
                throw new BusinessException("FACTOR_REQUERIDO", "Indique cuántas unidades base trae la presentación (p.ej. 100 libras por saco)");
            }
            if (r.unidadEmpaqueId().equals(r.unidadId())) {
                throw new BusinessException("EMPAQUE_INVALIDO", "La presentación debe ser una unidad distinta de la unidad base");
            }
            if (r.tipo() != Articulo.Tipo.B) throw new BusinessException("EMPAQUE_INVALIDO", "Los servicios no tienen presentación");
        }
        String cbe = r.unidadEmpaqueId() == null ? null : blank(r.codigoBarraEmpaque());
        if (cbe != null) {
            if (cbe.equals(cb)) throw new BusinessException("CODIGO_BARRA_DUPLICADO", "El código de la presentación debe ser distinto del de la unidad");
            if (!Objects.equals(cbe, a.getCodigoBarraEmpaque()) && (repo.existsByCompaniaIdAndCodigoBarra(companiaId, cbe)
                    || repo.existsByCompaniaIdAndCodigoBarraEmpaque(companiaId, cbe))) {
                throw new BusinessException(HttpStatus.CONFLICT, "CODIGO_BARRA_DUPLICADO", "Ya existe un artículo con el código " + cbe);
            }
        }
        if (cb != null && !Objects.equals(cb, a.getCodigoBarra()) && repo.existsByCompaniaIdAndCodigoBarraEmpaque(companiaId, cb)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CODIGO_BARRA_DUPLICADO", "Ya existe una presentación con el código " + cb);
        }
        if (a.getId() != null && a.getTipo() != r.tipo() && existencias.totalArticulo(a.getId()).signum() != 0) {
            throw new BusinessException("TIPO_CON_EXISTENCIA", "No se puede cambiar el tipo de un artículo con existencia");
        }
    }

    @Override
    protected void aplicar(Articulo a, Request r, Long companiaId) {
        a.setCodigoBarra(blank(r.codigoBarra()));
        a.setReferencia(blank(r.referencia()));
        a.setNombre(r.nombre().trim());
        a.setDescripcion(r.descripcion());
        a.setTipo(r.tipo());
        a.setDepartamentoId(r.departamentoId());
        a.setUnidadId(r.unidadId());
        a.setProveedorId(r.proveedorId());
        a.setIndicadorItbis(r.indicadorItbis().shortValue());
        a.setCosto(r.costo());
        if (a.getId() == null) a.setCostoPromedio(r.costo());
        boolean conItbis = Boolean.TRUE.equals(r.preciosIncluyenItbis());
        BigDecimal factor = BigDecimal.ONE.add(a.tasaItbis().movePointLeft(2));
        a.setPrecioContado(base(r.precioContado(), conItbis, factor));
        a.setPrecioCredito(base(cero(r.precioCredito()), conItbis, factor));
        a.setPrecioMayor(base(cero(r.precioMayor()), conItbis, factor));
        a.setDescuentoMaximo(cero(r.descuentoMaximo()));
        a.setStockMinimo(cero(r.stockMinimo()));
        a.setUbicacion(r.ubicacion());
        a.setImagenUrl(r.imagenUrl());
        a.setManejaVencimiento(Boolean.TRUE.equals(r.manejaVencimiento()) && r.tipo() == Articulo.Tipo.B);
        boolean empaque = r.unidadEmpaqueId() != null;
        a.setUnidadEmpaqueId(empaque ? r.unidadEmpaqueId() : null);
        a.setFactorEmpaque(empaque ? r.factorEmpaque() : null);
        a.setPrecioEmpaqueContado(empaque ? opcional(base(cero(r.precioEmpaqueContado()), conItbis, factor)) : null);
        a.setPrecioEmpaqueCredito(empaque ? opcional(base(cero(r.precioEmpaqueCredito()), conItbis, factor)) : null);
        a.setPrecioEmpaqueMayor(empaque ? opcional(base(cero(r.precioEmpaqueMayor()), conItbis, factor)) : null);
        a.setCodigoBarraEmpaque(empaque ? blank(r.codigoBarraEmpaque()) : null);
    }

    @Override
    protected Response respuesta(Articulo a) {
        BigDecimal existencia = a.getId() == null ? BigDecimal.ZERO : existencias.totalArticulo(a.getId());
        return new Response(a.getId(), a.getCodigoBarra(), a.getReferencia(), a.getNombre(), a.getDescripcion(),
                a.getTipo(), a.getDepartamentoId(), a.getUnidadId(), a.getProveedorId(), a.getIndicadorItbis(),
                a.getCosto(), a.getCostoPromedio(), a.getPrecioContado(), a.getPrecioCredito(), a.getPrecioMayor(),
                conItbis(a, a.getPrecioContado()), conItbis(a, a.getPrecioCredito()), conItbis(a, a.getPrecioMayor()),
                a.getDescuentoMaximo(), a.getStockMinimo(), existencia, a.getUbicacion(), a.getImagenUrl(), a.getEstado(),
                a.isManejaVencimiento(), nombreUnidad(a.getUnidadId()), a.getUnidadEmpaqueId(), nombreUnidad(a.getUnidadEmpaqueId()),
                a.getFactorEmpaque(),
                // Solo los precios propios de la presentación (null = se usa el precio base × factor)
                propio(a, a.getPrecioEmpaqueContado()), propio(a, a.getPrecioEmpaqueCredito()), propio(a, a.getPrecioEmpaqueMayor()),
                a.getCodigoBarraEmpaque());
    }

    /** Búsqueda por código de barras para el punto de venta (legacy ConsultaArticulo_PorCodigo). */
    public Response porCodigo(Long companiaId, String codigo) {
        // También por el código de la presentación (caja/saco): el punto de venta lo agrega como presentación
        return repo.findByCompaniaIdAndCodigoBarra(companiaId, codigo.trim())
                .or(() -> repo.findByCompaniaIdAndCodigoBarraEmpaque(companiaId, codigo.trim()))
                .filter(a -> "A".equals(a.getEstado()))
                .map(this::respuesta)
                .orElseThrow(() -> new NotFoundException("Artículo con código", codigo));
    }

    private static BigDecimal base(BigDecimal precio, boolean conItbis, BigDecimal factor) {
        return conItbis ? precio.divide(factor, 4, java.math.RoundingMode.HALF_UP) : precio;
    }

    private static BigDecimal conItbis(Articulo a, BigDecimal precio) {
        return precio.multiply(BigDecimal.ONE.add(a.tasaItbis().movePointLeft(2))).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static BigDecimal cero(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    private static BigDecimal propio(Articulo a, BigDecimal precio) { return a.tieneEmpaque() && precio != null ? conItbis(a, precio) : null; }

    /** Precio de presentación en cero = no definido (se usa precio base × factor). */
    private static BigDecimal opcional(BigDecimal b) { return b == null || b.signum() == 0 ? null : b; }

    public String nombreUnidad(Long unidadId) {
        if (unidadId == null) return null;
        try {
            return unidades.cargar(null, unidadId).getNombre();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
