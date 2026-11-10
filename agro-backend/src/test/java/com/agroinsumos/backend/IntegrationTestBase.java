package com.agroinsumos.backend;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Pruebas contra PostgreSQL real (docker compose up -d, o TEST_DB_URL). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;

    protected long companiaId;

    @BeforeEach
    void limpiarBase() {
        // Vacía todas las tablas excepto el historial de Flyway y los datos sembrados por las
        // migraciones (turnos V3, catálogo de opciones del menú V8)
        String tablas = String.join(", ", jdbc.queryForList("""
                select tablename from pg_tables where schemaname = 'public'
                  and tablename not in ('flyway_schema_history', 'turno', 'main_menu', 'sub_menu')""", String.class));
        jdbc.execute("truncate " + tablas + " restart identity cascade");
        companiaId = jdbc.queryForObject(
                "insert into compania(nombre, rnc, direccion) values ('AGROINSUMOS SRL','131000001','Av. Duarte 1, Santiago') returning id",
                Long.class);
    }

    /** Inserta un usuario con el hash EXACTO que generaba el sistema Swing: SHA1(password + idUsuario). */
    protected long usuarioLegacy(String username, String password, char nivel) throws Exception {
        long id = jdbc.queryForObject("insert into usuario(compania_id, nombre, username, nivel) values (?,?,?,?) returning id",
                Long.class, companiaId, "Usuario " + username, username, String.valueOf(nivel));
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest((password + id).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("update usuario set password_hash = ? where id = ?", "{sha1legacy}" + id + "$" + sha1, id);
        return id;
    }

    /** Asigna opciones de menú (sub_menu.codigo) al usuario. */
    protected void permitir(long usuarioId, String... codigos) {
        for (String c : codigos) {
            jdbc.update("""
                    insert into detalle_opciones_menu(sub_menu_id, usuario_id, permitido)
                    select id, ?, true from sub_menu where codigo = ?
                    on conflict (sub_menu_id, usuario_id) do update set permitido = true""", usuarioId, c);
        }
    }

    protected String login(String username, String password) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn().getResponse();
        if (res.getStatus() != 200) throw new AssertionError("login falló: " + res.getStatus() + " " + res.getContentAsString());
        return com.jayway.jsonpath.JsonPath.read(res.getContentAsString(), "$.accessToken");
    }
}
