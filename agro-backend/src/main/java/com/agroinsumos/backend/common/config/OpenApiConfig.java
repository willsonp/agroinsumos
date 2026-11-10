package com.agroinsumos.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static {
        // AuthUser se resuelve del JWT, no es un parámetro de la petición
        org.springdoc.core.utils.SpringDocUtils.getConfig()
                .addRequestWrapperToIgnore(com.agroinsumos.backend.security.AuthUser.class);
    }

    /**
     * Nombres de esquema únicos: los DTO anidados (ClienteService.Request, ArticuloService.Request...)
     * se publican como ClienteRequest, ArticuloRequest... En lugar de colisionar en un único "Request".
     */
    @Bean
    io.swagger.v3.core.jackson.ModelResolver nombresDeEsquemaUnicos() {
        return new io.swagger.v3.core.jackson.ModelResolver(io.swagger.v3.core.util.Json.mapper(),
                new io.swagger.v3.core.jackson.TypeNameResolver() {
                    @Override
                    protected String getNameOfClass(Class<?> cls) {
                        String nombre = super.getNameOfClass(cls);
                        Class<?> contenedora = cls.getEnclosingClass();
                        if (contenedora == null) return nombre;
                        String prefijo = contenedora.getSimpleName().replaceAll("(Service|Dtos|Controller)$", "");
                        return nombre.startsWith(prefijo) ? nombre : prefijo + nombre;
                    }
                });
    }

    @Bean
    OpenAPI agroOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Agro Backend API").version("v1")
                        .description("Sistema de Gestión Administrativa - PV Agroinsumos"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
