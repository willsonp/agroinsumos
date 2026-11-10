package com.agroinsumos.backend.usuario;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.compania.CompaniaRepository;
import com.agroinsumos.backend.security.token.RefreshTokenService;
import com.agroinsumos.backend.usuario.dto.UsuarioDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lógica extraída de Ctrl_Usuario (Guardar/Editar/Elimnar/ListarUsuario) y FrmUsuario.
 * Todas las operaciones quedan acotadas a la compañía del usuario autenticado (multi-tenant).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsuarioService {

    private final UsuarioRepository repo;
    private final CompaniaRepository companias;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final ApplicationEventPublisher eventos;

    public Page<UsuarioResponse> buscar(Long companiaId, String q, Pageable pageable) {
        String filtro = (q == null || q.isBlank()) ? null : q.trim();
        return repo.buscar(companiaId, filtro, pageable).map(UsuarioResponse::de);
    }

    public UsuarioResponse obtener(Long companiaId, Long id) {
        return UsuarioResponse.de(cargar(companiaId, id));
    }

    @Transactional
    public UsuarioResponse crear(Long companiaId, CrearUsuarioRequest req) {
        var d = req.datos();
        validarUnicidad(d.username(), d.correo(), null);
        var u = new Usuario();
        u.setCompania(companias.getReferenceById(companiaId));
        aplicar(u, d);
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        repo.save(u);
        publicar(u, companiaId);
        return UsuarioResponse.de(u);
    }

    @Transactional
    public UsuarioResponse actualizar(Long companiaId, Long id, UsuarioRequest d) {
        var u = cargar(companiaId, id);
        validarUnicidad(d.username(), d.correo(), u);
        aplicar(u, d);
        publicar(u, companiaId);
        return UsuarioResponse.de(u);
    }

    /** Borrado lógico (estado = 'I'), igual que Ctrl_Usuario.Elimnar, más revocación de sesiones. */
    @Transactional
    public void desactivar(Long companiaId, Long id, Long usuarioActualId) {
        if (id.equals(usuarioActualId)) {
            throw new BusinessException("AUTO_DESACTIVACION", "No puede desactivar su propio usuario");
        }
        var u = cargar(companiaId, id);
        u.setEstado(Usuario.INACTIVO);
        refreshTokens.revocarTodos(u.getId());
    }

    @Transactional
    public void resetPassword(Long companiaId, Long id, String nueva) {
        var u = cargar(companiaId, id);
        u.setPasswordHash(passwordEncoder.encode(nueva));
        u.setIntentosFallidos(0);
        u.setBloqueadoHasta(null);
        refreshTokens.revocarTodos(u.getId());
    }

    @Transactional
    public void cambiarPassword(Long usuarioId, CambiarPasswordRequest req) {
        var u = repo.findById(usuarioId).orElseThrow(() -> new NotFoundException("Usuario", usuarioId));
        if (u.getPasswordHash() == null || !passwordEncoder.matches(req.passwordActual(), u.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_ACTUAL_INVALIDA", "La contraseña actual no es correcta");
        }
        u.setPasswordHash(passwordEncoder.encode(req.passwordNueva()));
        refreshTokens.revocarTodos(u.getId());
    }

    Usuario cargar(Long companiaId, Long id) {
        return repo.findById(id)
                .filter(u -> u.getCompania().getId().equals(companiaId))
                .orElseThrow(() -> new NotFoundException("Usuario", id));
    }

    private void validarUnicidad(String username, String correo, Usuario actual) {
        boolean cambiaUsername = actual == null || !actual.getUsername().equalsIgnoreCase(username);
        if (cambiaUsername && repo.existsByUsernameIgnoreCase(username)) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_DUPLICADO", "El nombre de usuario ya existe");
        }
        boolean cambiaCorreo = correo != null && (actual == null || !correo.equalsIgnoreCase(actual.getCorreo()));
        if (cambiaCorreo && repo.existsByCorreoIgnoreCase(correo)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CORREO_DUPLICADO", "El correo ya está asignado a otro usuario");
        }
    }

    private static void aplicar(Usuario u, UsuarioRequest d) {
        u.setCedula(d.cedula());
        u.setNombre(d.nombre().trim());
        u.setApellidos(d.apellidos());
        u.setDireccion(d.direccion());
        u.setUsername(d.username().trim());
        u.setNivel(d.nivel());
        u.setSexo(d.sexo());
        u.setCorreo(d.correo() == null || d.correo().isBlank() ? null : d.correo().trim().toLowerCase());
        if (d.turnoId() != null) u.setTurnoId(d.turnoId());
    }

    private void publicar(Usuario u, Long companiaId) {
        eventos.publishEvent(new UsuarioCreadoEvent(u.getId(), companiaId, u.nombreCompleto(), u.getCedula(),
                u.getDireccion(), u.getCorreo()));
    }
}
