package com.agroinsumos.backend.auth.dto;

import com.agroinsumos.backend.menu.dto.MenuDtos.ModuloResponse;
import com.agroinsumos.backend.usuario.Nivel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 45) String username, @NotBlank @Size(max = 72) String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String refreshToken, long refreshExpiresIn) {}

    public record CompaniaInfo(Long id, String nombre, String rnc, String direccion, String correo,
                               String sitioWeb, BigDecimal itbis, boolean aceptaContabilidad, String modoComprobante) {}

    /** Sustituye a los valores que VentanaLogin guardaba en Ctrl_Parametros tras el login. */
    public record PerfilResponse(Long id, String username, String nombre, String correo, Nivel nivel,
                                 Integer turnoId, CompaniaInfo compania, Set<String> permisos) {}

    public record MenuResponse(List<ModuloResponse> modulos) {}

    public record ProveedorOAuth2(String id, String url) {}
}
