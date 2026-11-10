package com.agroinsumos.backend.security;

import com.agroinsumos.backend.IntegrationTestBase;
import com.agroinsumos.backend.common.crud.CrudController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Red de seguridad: todo endpoint de /api/v1 debe declarar su autorización (@PreAuthorize en el
 * método o la clase). Si alguien agrega un endpoint y olvida protegerlo, esta prueba falla.
 */
class CoberturaAutorizacionTest extends IntegrationTestBase {

    /** Excepciones justificadas. */
    private static final Set<String> PUBLICOS_O_PROPIOS = Set.of(
            "AuthController",                         // login/refresh/logout/me/menu: públicos o del propio usuario
            "ArticuloController.porCodigo",           // lectura de catálogo (punto de venta, compras)
            "ArticuloController.existencias");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void todoEndpointDeLaApiDeclaraAutorizacion() {
        List<String> sinProteger = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            boolean esApi = e.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/v1"));
            if (!esApi) continue;
            String clase = h.getBeanType().getSimpleName();
            String nombre = clase + "." + h.getMethod().getName();
            if (PUBLICOS_O_PROPIOS.contains(clase) || PUBLICOS_O_PROPIOS.contains(nombre)) continue;
            // CrudController: lecturas abiertas a autenticados; escrituras validadas con permisos.exigir(...)
            if (h.getMethod().getDeclaringClass() == CrudController.class) continue;
            boolean protegido = AnnotatedElementUtils.hasAnnotation(h.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(h.getBeanType(), PreAuthorize.class);
            if (!protegido) sinProteger.add(nombre + " " + e.getKey().getPatternValues());
        }
        assertThat(sinProteger).as("Endpoints sin @PreAuthorize").isEmpty();
    }
}
