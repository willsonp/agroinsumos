package com.agroinsumos.backend.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OpcionMenuUsuarioRepository extends JpaRepository<OpcionMenuUsuario, Long> {

    /** Reemplaza los procedimientos mostrar_menuprincipal(pusuario) y mostrar_Menu(pidmenu, pusuario). */
    @Query("""
            select s from OpcionMenuUsuario o join o.subMenu s join fetch s.mainMenu m
            where o.usuario.id = :usuarioId and o.permitido = true and s.estado = 'A' and m.estado = 'A'
            order by m.orden, m.nombreModulo, s.orden, s.nombreOpcion
            """)
    List<SubMenu> opcionesPermitidas(Long usuarioId);

    @Modifying
    @Query("delete from OpcionMenuUsuario o where o.usuario.id = :usuarioId")
    void eliminarDeUsuario(Long usuarioId);
}
