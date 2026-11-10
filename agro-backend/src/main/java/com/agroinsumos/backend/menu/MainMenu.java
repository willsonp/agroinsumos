package com.agroinsumos.backend.menu;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "main_menu")
public class MainMenu {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_modulo", nullable = false, length = 100)
    private String nombreModulo;

    @Column(nullable = false, length = 60)
    private String codigo;

    private int orden;

    @Column(nullable = false, length = 1)
    private String estado = "A";
}
