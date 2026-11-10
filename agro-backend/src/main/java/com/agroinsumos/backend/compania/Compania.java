package com.agroinsumos.backend.compania;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "compania")
public class Compania extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(name = "nombre_comercial", length = 150)
    private String nombreComercial;

    @Column(length = 200)
    private String direccion;

    @Column(nullable = false, length = 11)
    private String rnc;

    @Column(length = 100)
    private String correo;

    @Column(name = "sitio_web", length = 100)
    private String sitioWeb;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal itbis = new BigDecimal("18.00");

    @Column(name = "acepta_contabilidad", nullable = false)
    private boolean aceptaContabilidad;

    @Column(nullable = false, length = 1)
    private String estado = "A";
}
