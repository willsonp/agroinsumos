package com.agroinsumos.backend.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sub_menu")
public class SubMenu {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "main_menu_id")
    private MainMenu mainMenu;

    @Column(name = "nombre_opcion", nullable = false, length = 100)
    private String nombreOpcion;

    @Column(nullable = false, length = 60)
    private String codigo;

    @Column(name = "ruta_frontend", length = 150)
    private String rutaFrontend;

    private int orden;

    @Column(nullable = false, length = 1)
    private String estado = "A";
}
