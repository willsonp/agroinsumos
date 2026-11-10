package com.agroinsumos.backend.cxc;

import com.agroinsumos.backend.common.pago.FormaPago;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "recibo_pago")
public class ReciboPago {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recibo_id")
    private Recibo recibo;
    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago", nullable = false, length = 15)
    private FormaPago formaPago;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;
    @Column(length = 40)
    private String referencia;
    @Column(length = 45)
    private String banco;
    @Column(name = "cuenta_bancaria_id")
    private Long cuentaBancariaId;
}
