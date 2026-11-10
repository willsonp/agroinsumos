package com.agroinsumos.backend.security.oauth2;

import com.agroinsumos.backend.usuario.Usuario;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "usuario_identidad_externa")
public class IdentidadExterna {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(nullable = false, length = 30)
    private String proveedor;

    @Column(nullable = false)
    private String sujeto;

    @Column(length = 100)
    private String correo;

    @Column(name = "vinculado_en", nullable = false)
    private Instant vinculadoEn = Instant.now();
}
