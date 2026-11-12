package com.agroinsumos.backend.compania;

import com.agroinsumos.backend.common.exception.NotFoundException;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Datos de la empresa que aparecen en etiquetas, impresos y reportes. El RNC no se cambia aquí (lo usa el e-CF). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompaniaService {

    public record Request(@NotBlank @Size(max = 150) String nombre, @Size(max = 150) String nombreComercial,
                          @Size(max = 200) String direccion, @Size(max = 30) String telefono,
                          @Email @Size(max = 100) String correo, @Size(max = 100) String sitioWeb,
                          @Size(max = 100) String lema, @Size(max = 150) String pieTicket,
                          /** ECF (facturación electrónica), NCF (serie B) o NINGUNO (sin comprobante fiscal). Vacío = no cambia. */
                          @Pattern(regexp = "ECF|NCF|NINGUNO") String modoComprobante) {}

    public record Response(Long id, String nombre, String nombreComercial, String rnc, String direccion, String telefono,
                           String correo, String sitioWeb, String lema, String pieTicket, String modoComprobante) {
        static Response de(Compania c) {
            return new Response(c.getId(), c.getNombre(), c.getNombreComercial(), c.getRnc(), c.getDireccion(), c.getTelefono(),
                    c.getCorreo(), c.getSitioWeb(), c.getLema(), c.getPieTicket(), c.getModoComprobante());
        }
    }

    private final CompaniaRepository repo;

    public Compania cargar(Long id) { return repo.findById(id).orElseThrow(() -> new NotFoundException("Compañía", id)); }

    public Response obtener(Long id) { return Response.de(cargar(id)); }

    @Transactional
    public Response actualizar(Long id, Request r) {
        var c = cargar(id);
        c.setNombre(r.nombre().trim());
        c.setNombreComercial(vacio(r.nombreComercial()));
        c.setDireccion(vacio(r.direccion()));
        c.setTelefono(vacio(r.telefono()));
        c.setCorreo(vacio(r.correo()));
        c.setSitioWeb(vacio(r.sitioWeb()));
        c.setLema(vacio(r.lema()));
        c.setPieTicket(vacio(r.pieTicket()));
        if (r.modoComprobante() != null) c.setModoComprobante(r.modoComprobante());
        return Response.de(c);
    }

    private static String vacio(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
