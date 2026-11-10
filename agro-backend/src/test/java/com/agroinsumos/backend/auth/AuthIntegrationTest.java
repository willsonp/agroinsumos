package com.agroinsumos.backend.auth;

import com.agroinsumos.backend.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void loginConHashLegacyMigraABcrypt() throws Exception {
        long id = usuarioLegacy("cajero1", "clave-vieja", 'U');

        String token = login("cajero1", "clave-vieja");

        String hash = jdbc.queryForObject("select password_hash from usuario where id = ?", String.class, id);
        assertThat(hash).startsWith("{bcrypt}");
        // Sigue funcionando tras la migración del hash
        login("cajero1", "clave-vieja");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("cajero1"))
                .andExpect(jsonPath("$.nivel").value("USUARIO"))
                .andExpect(jsonPath("$.compania.rnc").value("131000001"));
    }

    @Test
    void credencialesInvalidasDevuelven401SinRevelarSiExisteElUsuario() throws Exception {
        usuarioLegacy("cajero1", "correcta", 'U');
        for (String user : new String[]{"cajero1", "noexiste"}) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                            .content("{\"username\":\"" + user + "\",\"password\":\"mala\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("CREDENCIALES_INVALIDAS"));
        }
    }

    @Test
    void bloqueaTrasCincoIntentosFallidos() throws Exception {
        usuarioLegacy("cajero1", "correcta", 'U');
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                    .content("{\"username\":\"cajero1\",\"password\":\"mala\"}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"username\":\"cajero1\",\"password\":\"correcta\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USUARIO_BLOQUEADO"));
    }

    @Test
    void refreshRotaYDetectaReutilizacion() throws Exception {
        usuarioLegacy("cajero1", "clave", 'U');
        var r1 = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"username\":\"cajero1\",\"password\":\"clave\"}")).andReturn().getResponse().getContentAsString();
        String refresh1 = JsonPath.read(r1, "$.refreshToken");

        var r2 = mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String refresh2 = JsonPath.read(r2, "$.refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Reutilizar el token viejo -> 401 y se revoca toda la familia (incluido refresh2)
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
                .content("{\"refreshToken\":\"" + refresh1 + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
                .content("{\"refreshToken\":\"" + refresh2 + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void sinTokenOTokenAlteradoDevuelve401() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        usuarioLegacy("cajero1", "clave", 'U');
        String token = login("cajero1", "clave");
        String alterado = token.substring(0, token.length() - 4) + "AAAA";
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + alterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void soloAdminGestionaUsuariosYPermisos() throws Exception {
        usuarioLegacy("cajero1", "clave", 'U');
        usuarioLegacy("admin", "adminclave", 'A');
        String cajero = login("cajero1", "clave");
        String admin = login("admin", "adminclave");

        mvc.perform(get("/api/v1/usuarios").header("Authorization", "Bearer " + cajero))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));

        var creado = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("""
                                {"datos":{"nombre":"Ana","apellidos":"Pérez","username":"ana","nivel":"SUPERVISOR",
                                 "correo":"Ana@Agro.do"},"password":"SuperClave123"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correo").value("ana@agro.do"))
                .andReturn().getResponse().getContentAsString();
        Integer anaId = JsonPath.read(creado, "$.id");

        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("""
                                {"datos":{"nombre":"Otra","username":"ANA","nivel":"USUARIO"},"password":"SuperClave123"}"""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_DUPLICADO"));

        // Menú (opciones sembradas por V8): asignar a Ana solo el punto de venta
        long pos = jdbc.queryForObject("select id from sub_menu where codigo = 'VENTAS_POS'", Long.class);
        mvc.perform(put("/api/v1/usuarios/" + anaId + "/permisos").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"subMenuIds\":[" + pos + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.codigo=='VENTAS_POS')].permitido").value(contains(true)))
                .andExpect(jsonPath("$[?(@.codigo=='VENTAS_FACTURAS')].permitido").value(contains(false)));

        String ana = login("ana", "SuperClave123");
        mvc.perform(get("/api/v1/auth/menu").header("Authorization", "Bearer " + ana))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modulos", hasSize(1)))
                .andExpect(jsonPath("$.modulos[0].opciones[*].codigo").value(contains("VENTAS_POS")));

        // Desactivar revoca el acceso por refresh
        mvc.perform(delete("/api/v1/usuarios/" + anaId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"username\":\"ana\",\"password\":\"SuperClave123\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void validacionDevuelveErroresPorCampo() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        String admin = login("admin", "adminclave");
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"datos\":{\"nombre\":\"\",\"username\":\"x y\",\"nivel\":\"ADMIN\"},\"password\":\"corta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.password").exists())
                .andExpect(jsonPath("['errores']['datos.username']").exists());
    }

    @Test
    void desactivarOCambiarNivelInvalidaElTokenAlInstante() throws Exception {
        long id = usuarioLegacy("cajero1", "clave", 'S');
        String token = login("cajero1", "clave");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        // Le bajan el nivel: el token que dice SUPERVISOR deja de servir (debe renovarlo con el nivel actual)
        jdbc.update("update usuario set nivel = 'U' where id = ?", id);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
        String nuevo = login("cajero1", "clave");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + nuevo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nivel").value("USUARIO"));
        // Lo desactivan: el token vigente se rechaza de inmediato
        jdbc.update("update usuario set estado = 'I' where id = ?", id);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + nuevo)).andExpect(status().isUnauthorized());
    }

    @Test
    void frenaLosIntentosFallidosPorIp() throws Exception {
        usuarioLegacy("cajero1", "correcta", 'U');
        for (int i = 0; i < 20; i++) {
            String u = "inexistente" + i;   // distintos usuarios: el bloqueo por usuario no aplica, el de IP sí
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }).contentType("application/json")
                    .content("{\"username\":\"" + u + "\",\"password\":\"mala\"}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }).contentType("application/json")
                        .content("{\"username\":\"cajero1\",\"password\":\"correcta\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("DEMASIADOS_INTENTOS"));
        // Desde otro equipo el usuario entra normalmente
        login("cajero1", "correcta");
    }
}
