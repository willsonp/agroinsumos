package com.agroinsumos.backend.security.oauth2;

import com.agroinsumos.backend.auth.AuthService;
import com.agroinsumos.backend.auth.dto.AuthDtos.TokenResponse;
import com.agroinsumos.backend.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Al terminar el flujo OAuth2 se emiten los tokens propios (JWT + refresh) y se redirige
 * al frontend configurado (nunca a una URL recibida en la petición: evita open-redirect).
 * Los tokens viajan en el fragmento (#...), que el navegador no envía a servidores ni en Referer.
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final OAuth2UsuarioResolver resolver;
    private final AuthService authService;
    private final SecurityProperties props;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth)
            throws IOException {
        var token = (OAuth2AuthenticationToken) auth;
        var usuario = resolver.resolver(token.getAuthorizedClientRegistrationId(), token.getPrincipal());
        invalidarSesion(req);
        if (usuario.isEmpty()) {
            redirigirError(res, "usuario_no_registrado");
            return;
        }
        TokenResponse t = authService.emitirTokens(usuario.get(), req);
        String fragmento = "access_token=" + t.accessToken()
                + "&token_type=Bearer&expires_in=" + t.expiresIn()
                + "&refresh_token=" + t.refreshToken();
        res.sendRedirect(UriComponentsBuilder.fromUriString(props.oauth2().authorizedRedirectUri())
                .fragment(fragmento).build().toUriString());
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
            throws IOException {
        invalidarSesion(req);
        redirigirError(res, "oauth2_fallido");
    }

    private void redirigirError(HttpServletResponse res, String codigo) throws IOException {
        res.sendRedirect(UriComponentsBuilder.fromUriString(props.oauth2().authorizedRedirectUri())
                .queryParam("error", codigo).build().toUriString());
    }

    private static void invalidarSesion(HttpServletRequest req) {
        var s = req.getSession(false);
        if (s != null) s.invalidate();
    }
}
