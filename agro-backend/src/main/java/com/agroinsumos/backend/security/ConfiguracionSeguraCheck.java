package com.agroinsumos.backend.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fuera de desarrollo y pruebas, el servidor no arranca con secretos que están publicados en el repositorio
 * (OWASP A05 Configuración insegura / A07): con el secreto JWT de desarrollo cualquiera podría fabricar un token
 * de administrador. También advierte sobre la contraseña por defecto de la base de datos.
 */
@Slf4j
@Component
@Profile("!dev & !test")
public class ConfiguracionSeguraCheck implements InitializingBean {

    static final List<String> SECRETOS_PUBLICOS = List.of("dev-secret-cambiar-0123456789-abcdefghijklmnop",
            "test-secret-0123456789-0123456789-abcdef");

    private final SecurityProperties props;
    private final String dbPassword;

    public ConfiguracionSeguraCheck(SecurityProperties props, @Value("${spring.datasource.password:}") String dbPassword) {
        this.props = props;
        this.dbPassword = dbPassword;
    }

    @Override
    public void afterPropertiesSet() {
        if (SECRETOS_PUBLICOS.contains(props.jwt().secret())) {
            throw new IllegalStateException("JWT_SECRET usa el valor de desarrollo, que es público. "
                    + "Genere uno propio (p.ej. openssl rand -base64 48) y configúrelo en la variable JWT_SECRET.");
        }
        if ("agro".equals(dbPassword)) {
            log.warn("SEGURIDAD la base de datos usa la contraseña por defecto 'agro': cámbiela y configure DB_PASSWORD");
        }
    }
}
