package com.agroinsumos.backend.inventario.kardex;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "existencia")
@IdClass(Existencia.Pk.class)
public class Existencia {
    public record Pk(Long articuloId, Long almacenId) implements Serializable {
        public Pk() { this(null, null); }
    }

    @Id
    @Column(name = "articulo_id")
    private Long articuloId;
    @Id
    @Column(name = "almacen_id")
    private Long almacenId;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidad = BigDecimal.ZERO;
}
