package com.agroinsumos.backend.usuario.dto;

import com.agroinsumos.backend.usuario.Nivel;
import com.agroinsumos.backend.usuario.Usuario;
import jakarta.validation.constraints.*;

import java.time.Instant;

public final class UsuarioDtos {
    private UsuarioDtos() {}

    public record UsuarioRequest(
            @Pattern(regexp = "\\d{11}", message = "La cédula debe tener 11 dígitos") String cedula,
            @NotBlank @Size(max = 45) String nombre,
            @Size(max = 45) String apellidos,
            @Size(max = 200) String direccion,
            @NotBlank @Size(min = 3, max = 45) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "Solo letras, números, punto, guion y guion bajo") String username,
            @NotNull Nivel nivel,
            @Pattern(regexp = "[MF]") String sexo,
            @Email @Size(max = 100) String correo,
            Integer turnoId) {}

    public record CrearUsuarioRequest(
            @NotNull @jakarta.validation.Valid UsuarioRequest datos,
            @NotBlank @Size(min = 12, max = 72, message = "La contraseña debe tener entre 12 y 72 caracteres") String password) {}

    public record CambiarPasswordRequest(
            @NotBlank String passwordActual,
            @NotBlank @Size(min = 12, max = 72, message = "La contraseña debe tener entre 12 y 72 caracteres") String passwordNueva) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 72, message = "La contraseña debe tener entre 12 y 72 caracteres") String passwordNueva) {}

    public record UsuarioResponse(Long id, String cedula, String nombre, String apellidos, String direccion,
                                  String username, Nivel nivel, String sexo, String correo, Integer turnoId,
                                  String estado, Instant ultimoAcceso) {
        public static UsuarioResponse de(Usuario u) {
            return new UsuarioResponse(u.getId(), u.getCedula(), u.getNombre(), u.getApellidos(), u.getDireccion(),
                    u.getUsername(), u.getNivel(), u.getSexo(), u.getCorreo(), u.getTurnoId(), u.getEstado(),
                    u.getUltimoAcceso());
        }
    }
}
