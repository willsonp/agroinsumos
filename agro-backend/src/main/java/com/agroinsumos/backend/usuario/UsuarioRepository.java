package com.agroinsumos.backend.usuario;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    @EntityGraph(attributePaths = "compania")
    Optional<Usuario> findByUsernameIgnoreCase(String username);

    @EntityGraph(attributePaths = "compania")
    Optional<Usuario> findByCorreoIgnoreCase(String correo);

    @EntityGraph(attributePaths = "compania")
    Optional<Usuario> findWithCompaniaById(Long id);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByCorreoIgnoreCase(String correo);

    /** Equivalente seguro (parametrizado) del ListarUsuario(caracteres) legacy con LIKE concatenado. */
    @Query("""
            select u from Usuario u
            where u.compania.id = :companiaId and u.estado = 'A'
              and (:q is null or lower(concat(coalesce(u.cedula,''),' ',u.nombre,' ',coalesce(u.apellidos,''),' ',u.username))
                   like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Usuario> buscar(Long companiaId, String q, Pageable pageable);
}
