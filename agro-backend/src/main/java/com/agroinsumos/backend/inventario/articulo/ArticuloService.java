package com.agroinsumos.backend.inventario.articulo;

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
            Boolean preciosIncluyenItbis) {}

    public record Response(Long id, String codigoBarra, String referencia, String nombre, String descripcion,
                           Articulo.Tipo tipo, Long departamentoId, Long unidadId, Long proveedorId, int indicadorItbis,
                           BigDecimal costo, BigDecimal costoPromedio, BigDecimal precioContado,
                           BigDecimal precioCredito, BigDecimal precioMayor, BigDecimal precioContadoConItbis,
                           BigDecimal precioCreditoConItbis, BigDecimal precioMayorConItbis, BigDecimal descuentoMaximo,
                           BigDecimal stockMinimo, BigDecimal existencia, String ubicacion, String imagenUrl,
                           String estado) {}

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
    }

    @Override
    protected Response respuesta(Articulo a) {
        BigDecimal existencia = a.getId() == null ? BigDecimal.ZERO : existencias.totalArticulo(a.getId());
        return new Response(a.getId(), a.getCodigoBarra(), a.getReferencia(), a.getNombre(), a.getDescripcion(),
                a.getTipo(), a.getDepartamentoId(), a.getUnidadId(), a.getProveedorId(), a.getIndicadorItbis(),
                a.getCosto(), a.getCostoPromedio(), a.getPrecioContado(), a.getPrecioCredito(), a.getPrecioMayor(),
                conItbis(a, a.getPrecioContado()), conItbis(a, a.getPrecioCredito()), conItbis(a, a.getPrecioMayor()),
                a.getDescuentoMaximo(), a.getStockMinimo(), existencia, a.getUbicacion(), a.getImagenUrl(), a.getEstado());
    }

    /** Búsqueda por código de barras para el punto de venta (legacy ConsultaArticulo_PorCodigo). */
    public Response porCodigo(Long companiaId, String codigo) {
        return repo.findByCompaniaIdAndCodigoBarra(companiaId, codigo.trim())
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
}
