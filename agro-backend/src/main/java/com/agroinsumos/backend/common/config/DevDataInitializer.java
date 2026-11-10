package com.agroinsumos.backend.common.config;

import com.agroinsumos.backend.compania.Compania;
import com.agroinsumos.backend.compania.CompaniaRepository;
import com.agroinsumos.backend.usuario.Nivel;
import com.agroinsumos.backend.usuario.Usuario;
import com.agroinsumos.backend.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Solo perfil dev: crea compañía y usuario "admin" si la BD está vacía. */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataInitializer implements ApplicationRunner {

    private final CompaniaRepository companias;
    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Value("${app.dev.admin-password}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (usuarios.count() > 0) return;
        var c = new Compania();
        c.setNombre("PV AGROINSUMOS (DEV)");
        c.setRnc("131000001");
        c.setDireccion("Santo Domingo, R.D.");
        companias.save(c);
        var u = new Usuario();
        u.setCompania(c);
        u.setNombre("Administrador");
        u.setUsername("admin");
        u.setNivel(Nivel.ADMIN);
        u.setPasswordHash(encoder.encode(adminPassword));
        usuarios.save(u);
        // Datos mínimos para poder facturar en desarrollo
        jdbc.update("insert into almacen(compania_id, nombre, principal) values (?, 'Almacén principal', true)", c.getId());
        jdbc.update("insert into unidad(nombre, abreviatura) values ('Unidad','UND'), ('Saco','SC'), ('Galón','GL') on conflict do nothing");
        jdbc.update("insert into departamento(compania_id, nombre, tipo) values (?, 'General', 'I')", c.getId());
        jdbc.update("""
                insert into cliente(compania_id, nombre, tipo_ecf_preferido, tipo_precio)
                values (?, 'CONSUMIDOR FINAL', 32, 'CONTADO')""", c.getId());
        for (int tipo : new int[]{31, 32, 33, 34, 41, 43, 44, 45}) {
            jdbc.update("""
                    insert into ecf_secuencia(compania_id, tipo_ecf, secuencia_desde, secuencia_hasta, fecha_vencimiento)
                    values (?, ?, 1, 100000, date '2030-12-31')""", c.getId(), tipo);
        }
        log.warn("DEV: creado usuario 'admin' (contraseña en app.dev.admin-password)");
    }
}
