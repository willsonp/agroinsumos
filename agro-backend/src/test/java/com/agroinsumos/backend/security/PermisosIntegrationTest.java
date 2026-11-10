package com.agroinsumos.backend.security;

import com.agroinsumos.backend.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Permisos por opción de menú evaluados en el servidor (además del rol). */
class PermisosIntegrationTest extends IntegrationTestBase {

    long cajeroId, supervisorId;
    String admin;
    final String periodo = YearMonth.now(ZoneId.of("America/Santo_Domingo")).format(DateTimeFormatter.ofPattern("yyyyMM"));

    @BeforeEach
    void preparar() throws Exception {
        usuarioLegacy("admin", "adminclave", 'A');
        cajeroId = usuarioLegacy("cajero", "cajeroclave", 'U');
        supervisorId = usuarioLegacy("super", "superclave", 'S');
        admin = login("admin", "adminclave");
    }

    @Test
    void elRolNoBastaSinLaOpcion() throws Exception {
        String sup = login("super", "superclave");
        mvc.perform(get("/api/v1/fiscal/606?periodo=" + periodo).header("Authorization", "Bearer " + sup))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
        permitir(supervisorId, "FISCAL_FORMATOS");
        mvc.perform(get("/api/v1/fiscal/606?periodo=" + periodo).header("Authorization", "Bearer " + sup))
                .andExpect(status().isOk());
    }

    @Test
    void laOpcionNoBastaSinElRolEnOperacionesSensibles() throws Exception {
        permitir(cajeroId, "FISCAL_FORMATOS", "VENTAS_FACTURAS");
        String cajero = login("cajero", "cajeroclave");
        mvc.perform(get("/api/v1/fiscal/607?periodo=" + periodo).header("Authorization", "Bearer " + cajero))
                .andExpect(status().isForbidden());
        // Consultar facturas sí (solo requiere la opción)
        mvc.perform(get("/api/v1/ventas/facturas").header("Authorization", "Bearer " + cajero))
                .andExpect(status().isOk());
    }

    @Test
    void cadaOpcionAbreSoloSuModulo() throws Exception {
        permitir(cajeroId, "VENTAS_POS");
        String cajero = login("cajero", "cajeroclave");
        // Con VENTAS_POS: ve su caja (el POS la consulta) pero no administra comprobantes ni facturas
        mvc.perform(get("/api/v1/caja/sesiones/actual").header("Authorization", "Bearer " + cajero))
                .andExpect(status().isNoContent()); // permitido; 204 = no tiene caja abierta
        mvc.perform(get("/api/v1/ecf/documentos").header("Authorization", "Bearer " + cajero)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ventas/facturas").header("Authorization", "Bearer " + cajero)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/caja/sesiones/abrir").header("Authorization", "Bearer " + cajero)
                .contentType("application/json").content("{\"montoInicial\":0}")).andExpect(status().isForbidden());
        // Cuerpo válido: Spring valida @Valid antes de evaluar @PreAuthorize (un cuerpo inválido daría 400)
        mvc.perform(post("/api/v1/cxc/recibos").header("Authorization", "Bearer " + cajero).contentType("application/json")
                .content("{\"clienteId\":1,\"aplicaciones\":[{\"documentoTipo\":\"FACTURA\",\"documentoId\":1,\"monto\":1}]}"))
                .andExpect(status().isForbidden());
        // Lectura de catálogos: abierta a cualquier usuario autenticado
        mvc.perform(get("/api/v1/catalogos/clientes").header("Authorization", "Bearer " + cajero)).andExpect(status().isOk());
    }

    @Test
    void retirarUnaOpcionTieneEfectoInmediatoSinEsperarAlToken() throws Exception {
        permitir(cajeroId, "CAT_CLIENTES");
        String cajero = login("cajero", "cajeroclave");
        String cliente = """
                {"nombre":"Cliente %s","tipoEcfPreferido":32,"tipoPrecio":"CONTADO","limiteCredito":0}""";
        mvc.perform(post("/api/v1/catalogos/clientes").header("Authorization", "Bearer " + cajero)
                .contentType("application/json").content(cliente.formatted("A"))).andExpect(status().isCreated());

        // El administrador le retira todas las opciones; el mismo JWT (aún vigente) queda sin acceso
        mvc.perform(put("/api/v1/usuarios/" + cajeroId + "/permisos").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"subMenuIds\":[]}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/catalogos/clientes").header("Authorization", "Bearer " + cajero)
                .contentType("application/json").content(cliente.formatted("B")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }

    @Test
    void documentosDeInventarioExigenSuOpcionYSupervisor() throws Exception {
        String sup = login("super", "superclave");
        permitir(supervisorId, "INV_ARTICULOS");
        mvc.perform(get("/api/v1/inventario/documentos").header("Authorization", "Bearer " + sup)).andExpect(status().isForbidden());
        permitir(supervisorId, "INV_DOCUMENTOS");
        mvc.perform(get("/api/v1/inventario/documentos").header("Authorization", "Bearer " + sup)).andExpect(status().isOk());
        permitir(cajeroId, "INV_DOCUMENTOS", "VENTAS_COTIZACIONES");
        String cajero = login("cajero", "cajeroclave");
        mvc.perform(get("/api/v1/inventario/documentos").header("Authorization", "Bearer " + cajero)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ventas/cotizaciones").header("Authorization", "Bearer " + cajero)).andExpect(status().isOk());
    }

    @Test
    void laGestionDeUsuariosEsSoloParaAdministradores() throws Exception {
        permitir(supervisorId, "ADMIN_USUARIOS");
        String sup = login("super", "superclave");
        // Evita la auto-elevación: un supervisor con la opción no puede darse nivel ADMIN
        mvc.perform(get("/api/v1/usuarios").header("Authorization", "Bearer " + sup)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/usuarios").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
    }

    @Test
    void elPerfilInformaLosPermisosEfectivos() throws Exception {
        permitir(cajeroId, "VENTAS_POS", "CAJA_SESION");
        String cajero = login("cajero", "cajeroclave");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + cajero))
                .andExpect(jsonPath("$.permisos.length()").value(2));
        mvc.perform(get("/api/v1/auth/menu").header("Authorization", "Bearer " + cajero))
                .andExpect(jsonPath("$.modulos.length()").value(2));
    }
}
