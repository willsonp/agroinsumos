package com.agroinsumos.backend.inventario.documento;

import com.agroinsumos.backend.catalogo.almacen.AlmacenService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.inventario.articulo.Articulo;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
import com.agroinsumos.backend.inventario.documento.DocumentoInventario.Estado;
import com.agroinsumos.backend.inventario.documento.DocumentoInventario.Tipo;
import com.agroinsumos.backend.inventario.kardex.InventarioService;
import com.agroinsumos.backend.inventario.kardex.InventarioService.Movimiento;
import com.agroinsumos.backend.inventario.kardex.TipoMovimiento;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentoInventarioService {

    public record LineaRequest(@NotNull Long articuloId, @NotNull @DecimalMin("0") BigDecimal cantidad,
                               @DecimalMin("0") BigDecimal costoUnitario) {}

    public record Request(@NotNull Tipo tipo, LocalDate fecha, Long almacenId, Long almacenDestinoId,
                          @Size(max = 200) String descripcion, @NotEmpty @Size(max = 500) List<@Valid LineaRequest> lineas) {}

    public record LineaResponse(Long id, Long articuloId, BigDecimal cantidad, BigDecimal cantidadTeorica,
                                BigDecimal diferencia, BigDecimal costoUnitario) {}

    public record Response(Long id, long numero, Tipo tipo, LocalDate fecha, Long almacenId, Long almacenDestinoId,
                           String descripcion, Estado estado, Instant aplicadoEn, String aplicadoPor,
                           List<LineaResponse> lineas) {}

    private final DocumentoInventarioRepository repo;
    private final ArticuloService articulos;
    private final AlmacenService almacenes;
    private final InventarioService inventario;
    private final NumeradorService numerador;
    private final Clock clock;

    public Page<Response> listar(Long companiaId, Tipo tipo, Estado estado, Pageable p) {
        return repo.buscar(companiaId, tipo, estado, p).map(this::respuesta);
    }

    public Response obtener(Long companiaId, Long id) {
        return respuesta(cargar(companiaId, id));
    }

    @Transactional
    public Response crear(Long companiaId, Request r) {
        var d = new DocumentoInventario();
        d.setCompaniaId(companiaId);
        d.setNumero(numerador.siguiente(companiaId, "DOC_INVENTARIO"));
        aplicar(d, r);
        return respuesta(repo.save(d));
    }

    @Transactional
    public Response actualizar(Long companiaId, Long id, Request r) {
        var d = cargar(companiaId, id);
        exigirBorrador(d);
        d.getLineas().clear();
        repo.flush();
        aplicar(d, r);
        return respuesta(d);
    }

    /** Aplica el documento al inventario (legacy Aplicar_Ajuste). Irreversible: corregir con otro documento. */
    @Transactional
    public Response aplicar(Long companiaId, Long id) {
        var d = cargar(companiaId, id);
        exigirBorrador(d);
        String docTipo = "INV_" + d.getTipo().name();
        for (var l : d.getLineas()) {
            Articulo a = articulos.cargarActiva(companiaId, l.getArticuloId());
            switch (d.getTipo()) {
                case ENTRADA -> inventario.mover(new Movimiento(a, d.getAlmacenId(), TipoMovimiento.ENTRADA,
                        l.getCantidad(), l.getCostoUnitario(), docTipo, d.getId()));
                case SALIDA -> inventario.mover(new Movimiento(a, d.getAlmacenId(), TipoMovimiento.SALIDA,
                        l.getCantidad(), null, docTipo, d.getId()));
                case TRANSFERENCIA -> {
                    inventario.mover(new Movimiento(a, d.getAlmacenId(), TipoMovimiento.TRANSFERENCIA_SALIDA,
                            l.getCantidad(), null, docTipo, d.getId()));
                    inventario.mover(new Movimiento(a, d.getAlmacenDestinoId(), TipoMovimiento.TRANSFERENCIA_ENTRADA,
                            l.getCantidad(), a.getCostoPromedio(), docTipo, d.getId()));
                }
                case AJUSTE -> {
                    // Teórico = existencia en el momento de aplicar (con bloqueo dentro de mover)
                    BigDecimal teorico = inventario.existencia(a.getId(), d.getAlmacenId());
                    l.setCantidadTeorica(teorico);
                    BigDecimal dif = l.getCantidad().subtract(teorico);
                    if (dif.signum() > 0) {
                        inventario.mover(new Movimiento(a, d.getAlmacenId(), TipoMovimiento.AJUSTE_ENTRADA, dif, null, docTipo, d.getId()));
                    } else if (dif.signum() < 0) {
                        inventario.mover(new Movimiento(a, d.getAlmacenId(), TipoMovimiento.AJUSTE_SALIDA, dif.negate(), null, docTipo, d.getId()));
                    }
                }
            }
        }
        d.setEstado(Estado.APLICADO);
        d.setAplicadoEn(Instant.now());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        d.setAplicadoPor(auth == null ? "sistema" : auth.getName());
        return respuesta(d);
    }

    @Transactional
    public void anular(Long companiaId, Long id) {
        var d = cargar(companiaId, id);
        exigirBorrador(d);
        d.setEstado(Estado.ANULADO);
    }

    private void aplicar(DocumentoInventario d, Request r) {
        Long companiaId = d.getCompaniaId();
        var origen = almacenes.resolver(companiaId, r.almacenId());
        d.setTipo(r.tipo());
        d.setFecha(r.fecha() != null ? r.fecha() : LocalDate.now(clock));
        d.setAlmacenId(origen.getId());
        d.setDescripcion(r.descripcion());
        if (r.tipo() == Tipo.TRANSFERENCIA) {
            if (r.almacenDestinoId() == null || r.almacenDestinoId().equals(origen.getId())) {
                throw new BusinessException("DESTINO_INVALIDO", "La transferencia requiere un almacén destino distinto al origen");
            }
            d.setAlmacenDestinoId(almacenes.cargarActiva(companiaId, r.almacenDestinoId()).getId());
        } else {
            d.setAlmacenDestinoId(null);
        }
        var vistos = new HashSet<Long>();
        for (var lr : r.lineas()) {
            Articulo a = articulos.cargarActiva(companiaId, lr.articuloId());
            if (!a.inventariable()) throw new BusinessException("ARTICULO_SERVICIO", "'" + a.getNombre() + "' es un servicio y no maneja inventario");
            if (!vistos.add(a.getId())) throw new BusinessException("ARTICULO_REPETIDO", "'" + a.getNombre() + "' está repetido");
            if (r.tipo() != Tipo.AJUSTE && lr.cantidad().signum() <= 0) {
                throw new BusinessException("CANTIDAD_INVALIDA", "La cantidad de '" + a.getNombre() + "' debe ser mayor que cero");
            }
            var l = new DocumentoInventarioLinea();
            l.setDocumento(d);
            l.setArticuloId(a.getId());
            l.setCantidad(lr.cantidad());
            l.setCostoUnitario(lr.costoUnitario() != null ? lr.costoUnitario() : a.getCostoPromedio());
            d.getLineas().add(l);
        }
    }

    private static void exigirBorrador(DocumentoInventario d) {
        if (d.getEstado() != Estado.BORRADOR) {
            throw new BusinessException("DOCUMENTO_NO_EDITABLE", "El documento está " + d.getEstado());
        }
    }

    private DocumentoInventario cargar(Long companiaId, Long id) {
        return repo.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("Documento de inventario", id));
    }

    private Response respuesta(DocumentoInventario d) {
        return new Response(d.getId(), d.getNumero(), d.getTipo(), d.getFecha(), d.getAlmacenId(), d.getAlmacenDestinoId(),
                d.getDescripcion(), d.getEstado(), d.getAplicadoEn(), d.getAplicadoPor(),
                d.getLineas().stream().map(l -> new LineaResponse(l.getId(), l.getArticuloId(), l.getCantidad(),
                        l.getCantidadTeorica(),
                        l.getCantidadTeorica() == null ? null : l.getCantidad().subtract(l.getCantidadTeorica()),
                        l.getCostoUnitario())).toList());
    }
}
