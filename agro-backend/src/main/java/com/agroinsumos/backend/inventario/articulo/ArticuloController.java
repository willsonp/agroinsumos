package com.agroinsumos.backend.inventario.articulo;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.inventario.kardex.ExistenciaRepository;
import com.agroinsumos.backend.inventario.kardex.MovimientoInventario;
import com.agroinsumos.backend.inventario.kardex.MovimientoInventarioRepository;
import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Tag(name = "Inventario")
@RestController
@RequestMapping("/api/v1/inventario/articulos")
@RequiredArgsConstructor
public class ArticuloController extends CrudController<ArticuloService.Request, ArticuloService.Response> {

    private final ArticuloService service;
    private final ExistenciaRepository existencias;
    private final MovimientoInventarioRepository movimientos;
    private final com.agroinsumos.backend.inventario.kardex.LoteService lotes;

    public record ExistenciaResponse(Long almacenId, BigDecimal cantidad) {}

    public record KardexResponse(Long id, Instant fecha, Long almacenId, String tipo, BigDecimal cantidad,
                                 BigDecimal costoUnitario, BigDecimal existenciaFinal, String documentoTipo,
                                 Long documentoId, String usuario) {
        static KardexResponse de(MovimientoInventario m) {
            return new KardexResponse(m.getId(), m.getFecha(), m.getAlmacenId(), m.getTipo().name(), m.getCantidad(),
                    m.getCostoUnitario(), m.getExistenciaFinal(), m.getDocumentoTipo(), m.getDocumentoId(), m.getUsuario());
        }
    }

    @Override protected CrudService<?, ArticuloService.Request, ArticuloService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "INV_ARTICULOS"; }

    @Operation(summary = "Busca un artículo activo por código de barras (punto de venta)")
    @GetMapping("/codigo/{codigo}")
    public ArticuloService.Response porCodigo(AuthUser user, @PathVariable String codigo) {
        return service.porCodigo(user.companiaId(), codigo);
    }

    @Operation(summary = "Existencia por almacén")
    @GetMapping("/{id}/existencias")
    public List<ExistenciaResponse> existencias(AuthUser user, @PathVariable Long id) {
        service.cargar(user.companiaId(), id);
        return existencias.findByArticuloId(id).stream()
                .map(e -> new ExistenciaResponse(e.getAlmacenId(), e.getCantidad())).toList();
    }

    @Operation(summary = "Lotes del artículo con su fecha de vencimiento (solo artículos que controlan vencimiento)")
    @PreAuthorize("@permisos.tiene('INV_ARTICULOS','COMPRAS_REGISTRO','REP_INVENTARIO')")
    @GetMapping("/{id}/lotes")
    public List<com.agroinsumos.backend.inventario.kardex.LoteService.LoteResponse> lotes(AuthUser user, @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean soloConExistencia) {
        service.cargar(user.companiaId(), id);
        return lotes.lotes(id, soloConExistencia);
    }

    @Operation(summary = "Kardex (movimientos) del artículo, del más reciente al más antiguo")
    @PreAuthorize("@permisos.tiene('INV_ARTICULOS')")
    @GetMapping("/{id}/kardex")
    public Page<KardexResponse> kardex(AuthUser user, @PathVariable Long id,
                                       @RequestParam(required = false) Long almacenId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        service.cargar(user.companiaId(), id);
        return movimientos.kardex(id, almacenId, PageRequest.of(page, Math.min(size, 200))).map(KardexResponse::de);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Artículos activos por texto, departamento y existencia (selección para etiquetas)")
    @GetMapping("/seleccion")
    @PreAuthorize("@permisos.tiene('INV_ETIQUETAS','INV_ARTICULOS')")
    public org.springframework.data.domain.Page<ArticuloService.Response> seleccion(AuthUser user,
            @RequestParam(required = false) String q, @RequestParam(required = false) Long departamentoId,
            @RequestParam(defaultValue = "false") boolean conExistencia,
            @org.springframework.data.web.PageableDefault(size = 50, sort = "nombre") org.springframework.data.domain.Pageable p) {
        return service.seleccionar(user.companiaId(), q, departamentoId, conExistencia, p);
    }
}
