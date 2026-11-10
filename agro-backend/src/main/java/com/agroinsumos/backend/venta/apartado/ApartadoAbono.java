package com.agroinsumos.backend.venta.apartado;

import com.agroinsumos.backend.common.pago.FormaPago;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Abono a un apartado (o devolución del dinero al cancelarlo). */
@Getter
@Setter
@Entity
@Table(name = "apartado_abono")
public class ApartadoAbono {
    public enum Tipo { ABONO, DEVOLUCION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "apartado_id", nullable = false)
    private Long apartadoId;
    @Column(name = "compania_id", nullable = false)
    private Long companiaId;
    @Column(nullable = false)
    private LocalDate fecha;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Tipo tipo = Tipo.ABONO;
    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago", nullable = false, length = 15)
    private FormaPago formaPago;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;
    @Column(length = 40)
    private String referencia;
    @Column(name = "sesion_caja_id")
    private Long sesionCajaId;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();
}
