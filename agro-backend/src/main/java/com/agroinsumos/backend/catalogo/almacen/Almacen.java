package com.agroinsumos.backend.catalogo.almacen;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "almacen")
public class Almacen extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false, length = 45)
    private String nombre;
    @Column(length = 100)
    private String ubicacion;
    /** Almacén por defecto para ventas cuando no se indica otro. */
    @Column(nullable = false)
    private boolean principal;
    @Column(nullable = false, length = 1)
    private String estado = "A";
}
