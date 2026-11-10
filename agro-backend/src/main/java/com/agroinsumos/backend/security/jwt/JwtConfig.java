package com.agroinsumos.backend.security.jwt;

import com.agroinsumos.backend.usuario.Nivel;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import java.util.List;
import com.agroinsumos.backend.security.JwtClaims;
import com.agroinsumos.backend.security.SecurityProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT firmados con HMAC-SHA256 (clave simétrica). Para varios servicios que deban
 * validar tokens sin conocer el secreto, cambiar a RS256 con par de claves (JWKS).
 */
@Configuration
public class JwtConfig {

    private final SecretKey key;
    private final String issuer;

    public JwtConfig(SecurityProperties props) {
        this.key = new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.issuer = props.jwt().issuer();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<>(JwtClaims.USUARIO_ID, java.util.Objects::nonNull),
                new JwtClaimValidator<>(JwtClaims.COMPANIA_ID, java.util.Objects::nonNull)));
        return decoder;
    }

    /**
     * Convierte el JWT en la autenticación y, en cada petición, lo contrasta con la base: el usuario debe existir,
     * seguir activo, no estar bloqueado y conservar la misma compañía y nivel. Así, desactivar a un usuario o
     * cambiarle el nivel tiene efecto inmediato (sin esperar a que venza el token de 15 minutos); el frontend
     * recibe 401 y, si el usuario sigue activo, renueva el token con el nivel actual.
     */
    @Bean
    UsuarioVigenteJwtConverter jwtAuthenticationConverter(JdbcTemplate jdbc) {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtClaims.ROLES);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new UsuarioVigenteJwtConverter(converter, jdbc);
    }

    public static final class UsuarioVigenteJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        private final JwtAuthenticationConverter delegado;
        private final JdbcTemplate jdbc;

        UsuarioVigenteJwtConverter(JwtAuthenticationConverter delegado, JdbcTemplate jdbc) {
            this.delegado = delegado;
            this.jdbc = jdbc;
        }

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            long uid = ((Number) jwt.getClaim(JwtClaims.USUARIO_ID)).longValue();
            long cia = ((Number) jwt.getClaim(JwtClaims.COMPANIA_ID)).longValue();
            List<String> roles = jwt.getClaimAsStringList(JwtClaims.ROLES);
            var filas = jdbc.queryForList("""
                    select nivel, compania_id from usuario
                     where id = ? and estado = 'A' and (bloqueado_hasta is null or bloqueado_hasta <= now())""", uid);
            if (filas.isEmpty()) throw new InvalidBearerTokenException("Usuario inactivo o bloqueado");
            var f = filas.getFirst();
            Nivel nivel = Nivel.desdeCodigo(f.get("nivel").toString().charAt(0));
            if (((Number) f.get("compania_id")).longValue() != cia || roles == null || !List.of(nivel.rol()).equals(roles)) {
                throw new InvalidBearerTokenException("Token desactualizado");
            }
            return delegado.convert(jwt);
        }
    }
}
