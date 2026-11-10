package com.agroinsumos.backend.catalogo.proveedor;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "proveedor")
public class Proveedor extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false, length = 150)
    private String nombre;
    @Column(name = "rnc_cedula", length = 11)
    private String rncCedula;
    @Column(length = 255)
    private String direccion;
    @Column(length = 60)
    private String sector;
    @Column(name = "ciudad_id")
    private Long ciudadId;
    @Column(length = 100)
    private String correo;
    @Column(length = 20)
    private String telefono;
    @Column(length = 100)
    private String contacto;
    @Column(name = "cuenta_contable", length = 12)
    private String cuentaContable;
    @Column(nullable = false, length = 1)
    private String estado = "A";
}
