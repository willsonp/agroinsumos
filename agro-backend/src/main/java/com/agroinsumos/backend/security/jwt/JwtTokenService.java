package com.agroinsumos.backend.security.jwt;

import com.agroinsumos.backend.security.JwtClaims;
import com.agroinsumos.backend.security.SecurityProperties;
import com.agroinsumos.backend.usuario.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final SecurityProperties props;

    public record AccessToken(String valor, Instant expiraEn) {}

    public AccessToken emitir(Usuario u) {
        Instant ahora = Instant.now();
        Instant exp = ahora.plus(props.jwt().accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().issuer())
                .id(UUID.randomUUID().toString())
                .subject(u.getUsername())
                .issuedAt(ahora)
                .expiresAt(exp)
                .claim(JwtClaims.USUARIO_ID, u.getId())
                .claim(JwtClaims.COMPANIA_ID, u.getCompania().getId())
                .claim(JwtClaims.ROLES, List.of(u.getNivel().rol()))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), exp);
    }
}
