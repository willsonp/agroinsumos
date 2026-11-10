package com.agroinsumos.backend.catalogo.vendedor;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "vendedor")
public class Vendedor extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(nullable = false, length = 90)
    private String nombre;
    @Column(name = "rnc_cedula", length = 11)
    private String rncCedula;
    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal comision = BigDecimal.ZERO;
    @Column(name = "ciudad_id")
    private Long ciudadId;
    @Column(length = 150)
    private String direccion;
    @Column(length = 100)
    private String correo;
    @Column(length = 20)
    private String telefono;
    @Column(nullable = false, length = 1)
    private String estado = "A";
}
