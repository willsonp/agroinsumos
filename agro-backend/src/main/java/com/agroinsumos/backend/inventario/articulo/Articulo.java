package com.agroinsumos.backend.inventario.articulo;

import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "articulo")
public class Articulo extends Auditable implements Catalogo {
    /** B = Bien (mueve inventario), S = Servicio. Legacy: Tipo 'I'/'S'. */
    public enum Tipo { B, S }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(name = "codigo_barra", length = 20)
    private String codigoBarra;
    @Column(length = 20)
    private String referencia;
    @Column(nullable = false, length = 80)
    private String nombre;
    @Column(length = 500)
    private String descripcion;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 1)
    private Tipo tipo = Tipo.B;
    @Column(name = "departamento_id", nullable = false)
    private Long departamentoId;
    @Column(name = "unidad_id", nullable = false)
    private Long unidadId;
    @Column(name = "proveedor_id")
    private Long proveedorId;
    /** 1 ITBIS 18%, 2 ITBIS 16%, 3 ITBIS 0%, 4 Exento. */
    @Column(name = "indicador_itbis", nullable = false)
    private short indicadorItbis = 1;
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal costo = BigDecimal.ZERO;
    @Column(name = "costo_promedio", nullable = false, precision = 18, scale = 4)
    private BigDecimal costoPromedio = BigDecimal.ZERO;
    @Column(name = "precio_contado", nullable = false, precision = 18, scale = 4)
    private BigDecimal precioContado = BigDecimal.ZERO;
    @Column(name = "precio_credito", nullable = false, precision = 18, scale = 4)
    private BigDecimal precioCredito = BigDecimal.ZERO;
    @Column(name = "precio_mayor", nullable = false, precision = 18, scale = 4)
    private BigDecimal precioMayor = BigDecimal.ZERO;
    @Column(name = "descuento_maximo", nullable = false, precision = 5, scale = 2)
    private BigDecimal descuentoMaximo = BigDecimal.ZERO;
    @Column(name = "stock_minimo", nullable = false, precision = 14, scale = 3)
    private BigDecimal stockMinimo = BigDecimal.ZERO;
    @Column(length = 50)
    private String ubicacion;
    /** Controla lotes con fecha de vencimiento: la compra exige la fecha y las salidas descuentan por lote. */
    @Column(name = "maneja_vencimiento", nullable = false)
    private boolean manejaVencimiento;
    @Column(name = "imagen_url")
    private String imagenUrl;
    @Column(nullable = false, length = 1)
    private String estado = "A";
    @Version
    private long version;

    public BigDecimal tasaItbis() {
        return switch (indicadorItbis) {
            case 1 -> new BigDecimal("18");
            case 2 -> new BigDecimal("16");
            default -> BigDecimal.ZERO;
        };
    }

    public boolean inventariable() { return tipo == Tipo.B; }

    public BigDecimal precio(TipoPrecio lista) {
        BigDecimal p = switch (lista) {
            case CONTADO -> precioContado;
            case CREDITO -> precioCredito;
            case MAYOR -> precioMayor;
        };
        // Si la lista no tiene precio definido se usa el de contado
        return p == null || p.signum() == 0 ? precioContado : p;
    }
}
