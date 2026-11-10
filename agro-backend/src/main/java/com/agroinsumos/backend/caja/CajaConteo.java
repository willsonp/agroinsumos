package com.agroinsumos.backend.caja;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "caja_conteo")
public class CajaConteo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "sesion_id", nullable = false)
    private Long sesionId;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal denominacion;
    @Column(nullable = false)
    private int cantidad;
}
