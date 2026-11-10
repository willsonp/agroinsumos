package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "banco")
public class Banco extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 60)
    private String nombre;
    @Column(nullable = false, length = 1)
    private String estado = "A";
}
