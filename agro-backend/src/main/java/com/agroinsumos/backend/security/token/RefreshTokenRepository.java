package com.agroinsumos.backend.security.token;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @EntityGraph(attributePaths = {"usuario", "usuario.compania"})
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revocadoEn = :ahora where t.familia = :familia and t.revocadoEn is null")
    int revocarFamilia(UUID familia, Instant ahora);

    @Modifying
    @Query("update RefreshToken t set t.revocadoEn = :ahora where t.usuario.id = :usuarioId and t.revocadoEn is null")
    int revocarDeUsuario(Long usuarioId, Instant ahora);

    @Modifying
    @Query("delete from RefreshToken t where t.expiraEn < :limite")
    int purgarExpirados(Instant limite);
}
