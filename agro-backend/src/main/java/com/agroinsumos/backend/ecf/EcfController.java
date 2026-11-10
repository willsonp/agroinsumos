package com.agroinsumos.backend.ecf;

import com.agroinsumos.backend.ecf.dto.EcfDtos.*;
import com.agroinsumos.backend.ecf.emision.EcfEmisionService;
import com.agroinsumos.backend.ecf.secuencia.EcfSecuenciaService;
import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Tag(name = "Facturación Electrónica (e-CF DGII)")
@RestController
@RequestMapping("/api/v1/ecf")
@RequiredArgsConstructor
public class EcfController {

    private final EcfEmisionService emision;
    private final EcfEnvioService envio;
    private final EcfConsultaService consulta;
    private final EcfSecuenciaService secuencias;

    // ---------------------------------------------------------------- Secuencias
    @GetMapping("/secuencias")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('ECF_SECUENCIAS')")
    public List<SecuenciaResponse> secuencias(AuthUser user) {
        return secuencias.listar(user.companiaId());
    }

    @Operation(summary = "Registra un rango de e-NCF autorizado por la DGII (reemplaza al rango activo del tipo)")
    @PostMapping("/secuencias")
    @PreAuthorize("hasRole('ADMIN') and @permisos.tiene('ECF_SECUENCIAS')")
    public ResponseEntity<SecuenciaResponse> registrarSecuencia(AuthUser user, @Valid @RequestBody SecuenciaRequest req) {
        return ResponseEntity.status(201).body(secuencias.registrar(user.companiaId(), req));
    }

    // ---------------------------------------------------------------- Documentos
    @Operation(summary = "Genera, firma y guarda un e-CF (estado FIRMADO). No lo envía a la DGII")
    @PostMapping("/documentos")
    @PreAuthorize("hasRole('ADMIN') and @permisos.tiene('ECF_DOCUMENTOS')")
    public ResponseEntity<EcfResponse> emitir(AuthUser user, @Valid @RequestBody EmitirEcfRequest req) {
        var r = emision.emitir(user.companiaId(), req);
        return ResponseEntity.created(URI.create("/api/v1/ecf/documentos/" + r.id())).body(r);
    }

    @GetMapping("/documentos")
    @PreAuthorize("@permisos.tiene('ECF_DOCUMENTOS')")
    public Page<EcfResponse> listar(AuthUser user, @RequestParam(required = false) EstadoEcf estado,
                                    @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return consulta.listar(user.companiaId(), estado, p);
    }

    @GetMapping("/documentos/{id}")
    @PreAuthorize("@permisos.tiene('ECF_DOCUMENTOS','VENTAS_FACTURAS','VENTAS_POS')")
    public EcfResponse obtener(AuthUser user, @PathVariable Long id) {
        return consulta.obtener(user.companiaId(), id);
    }

    @PreAuthorize("@permisos.tiene('ECF_DOCUMENTOS','VENTAS_FACTURAS')")
    @GetMapping(value = "/documentos/{id}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> xml(AuthUser user, @PathVariable Long id,
                                      @RequestParam(defaultValue = "false") boolean rfce) {
        var xml = consulta.xml(user.companiaId(), id, rfce);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ecf-" + id + (rfce ? "-rfce" : "") + ".xml\"")
                .contentType(MediaType.APPLICATION_XML).body(xml);
    }

    @Operation(summary = "Envía el e-CF (o su RFCE) a la DGII")
    @PostMapping("/documentos/{id}/enviar")
    @PreAuthorize("@permisos.tiene('ECF_DOCUMENTOS')")
    public EcfResponse enviar(AuthUser user, @PathVariable Long id) {
        return envio.enviar(user.companiaId(), id);
    }

    @Operation(summary = "Consulta en la DGII el resultado de un e-CF enviado (por trackId)")
    @PostMapping("/documentos/{id}/consultar-estado")
    @PreAuthorize("@permisos.tiene('ECF_DOCUMENTOS')")
    public EcfResponse consultarEstado(AuthUser user, @PathVariable Long id) {
        return envio.consultar(user.companiaId(), id);
    }

    @Operation(summary = "URL del código QR (timbre) para la representación impresa")
    @GetMapping("/documentos/{id}/timbre")
    @PreAuthorize("@permisos.tiene('ECF_DOCUMENTOS','VENTAS_FACTURAS','VENTAS_POS')")
    public TimbreResponse timbre(AuthUser user, @PathVariable Long id) {
        return consulta.timbre(user.companiaId(), id);
    }
}
