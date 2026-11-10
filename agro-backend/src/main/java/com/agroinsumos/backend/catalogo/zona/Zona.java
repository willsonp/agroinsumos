package com.agroinsumos.backend.catalogo.zona;


import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "zona")
public class Zona implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false, length = 1)
    private String estado = "A";
}
