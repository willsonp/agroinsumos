package com.agroinsumos.backend.banco;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "conciliacion_bancaria")
public class ConciliacionBancaria {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false)
    private Long companiaId;
    @Column(name = "cuenta_id", nullable = false)
    private Long cuentaId;
    @Column(name = "fecha_corte", nullable = false)
    private LocalDate fechaCorte;
    @Column(name = "saldo_banco", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoBanco;
    @Column(name = "saldo_libros", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoLibros;
    @Column(name = "depositos_transito", nullable = false, precision = 18, scale = 2)
    private BigDecimal depositosTransito;
    @Column(name = "cheques_transito", nullable = false, precision = 18, scale = 2)
    private BigDecimal chequesTransito;
    @Column(length = 200)
    private String observacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();
}
