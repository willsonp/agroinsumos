package com.agroinsumos.backend.menu;

import com.agroinsumos.backend.usuario.Usuario;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tabla legacy detalle_opciones_menu: qué opciones de menú puede usar cada usuario. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "detalle_opciones_menu")
public class OpcionMenuUsuario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_menu_id")
    private SubMenu subMenu;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(nullable = false)
    private boolean permitido = true;

    public OpcionMenuUsuario(SubMenu subMenu, Usuario usuario) {
        this.subMenu = subMenu;
        this.usuario = usuario;
    }
}
