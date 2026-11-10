package com.agroinsumos.backend.catalogo.departamento;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "departamento")
public class Departamento extends Auditable implements Catalogo {
    /** I = Inventario, S = Servicio, E = Empleado (legacy TipoDepartamento). */
    public enum Tipo { I, S, E }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false, length = 45)
    private String nombre;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 1)
    private Tipo tipo = Tipo.I;
    @Column(name = "cuenta_costo", length = 12)
    private String cuentaCosto;
    @Column(name = "cuenta_ingreso", length = 12)
    private String cuentaIngreso;
    @Column(name = "cuenta_inventario", length = 12)
    private String cuentaInventario;
    @Column(nullable = false, length = 1)
    private String estado = "A";
}
