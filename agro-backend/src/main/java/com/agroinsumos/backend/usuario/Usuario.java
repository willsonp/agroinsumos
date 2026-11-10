package com.agroinsumos.backend.usuario;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.compania.Compania;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "usuario")
public class Usuario extends Auditable {

    public static final String ACTIVO = "A";
    public static final String INACTIVO = "I";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compania_id")
    private Compania compania;

    @Column(length = 11)
    private String cedula;

    @Column(nullable = false, length = 45)
    private String nombre;

    @Column(length = 45)
    private String apellidos;

    @Column(length = 200)
    private String direccion;

    @Column(nullable = false, length = 45)
    private String username;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 1)
    private Nivel nivel = Nivel.USUARIO;

    @Column(length = 1)
    private String sexo;

    @Column(length = 100)
    private String correo;

    @Column(name = "ruta_imagen")
    private String rutaImagen;

    @Column(name = "turno_id")
    private Integer turnoId = 1;

    @Column(nullable = false, length = 1)
    private String estado = ACTIVO;

    @Column(name = "intentos_fallidos", nullable = false)
    private int intentosFallidos;

    @Column(name = "bloqueado_hasta")
    private Instant bloqueadoHasta;

    @Column(name = "ultimo_acceso")
    private Instant ultimoAcceso;

    @Version
    private long version;

    public boolean isActivo() { return ACTIVO.equals(estado); }

    public boolean isBloqueado(Instant ahora) { return bloqueadoHasta != null && bloqueadoHasta.isAfter(ahora); }

    public String nombreCompleto() {
        return apellidos == null ? nombre : nombre + " " + apellidos;
    }
}
