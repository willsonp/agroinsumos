package com.agroinsumos.backend.caja;

import com.agroinsumos.backend.common.pago.FormaPago;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "caja_movimiento")
public class CajaMovimiento {
    public enum Tipo {
        COBRO(1), INGRESO(1), EGRESO(-1), PAGO_PROVEEDOR(-1), ANULACION_COBRO(-1), ANULACION_PAGO(1);
        private final int signo;
        Tipo(int signo) { this.signo = signo; }
        public int signo() { return signo; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "sesion_id", nullable = false)
    private Long sesionId;
    @Column(nullable = false)
    private Instant fecha = Instant.now();
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Tipo tipo;
    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago", nullable = false, length = 15)
    private FormaPago formaPago;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;
    @Column(name = "referencia_tipo", nullable = false, length = 20)
    private String referenciaTipo;
    @Column(name = "referencia_id", nullable = false)
    private Long referenciaId;
    @Column(length = 200)
    private String descripcion;
}
