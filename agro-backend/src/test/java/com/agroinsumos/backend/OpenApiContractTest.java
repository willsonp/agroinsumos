package com.agroinsumos.backend;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** El frontend genera sus tipos desde /v3/api-docs: los esquemas no pueden colisionar. */
class OpenApiContractTest extends IntegrationTestBase {

    @Test
    void esquemasConNombresUnicos() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        Map<String, Object> schemas = JsonPath.read(json, "$.components.schemas");
        assertThat(schemas).doesNotContainKeys("Request", "Response", "Linea", "Resumen", "Reporte")
                .containsKeys("ClienteRequest", "ArticuloRequest", "FacturaResponse", "Formato606Reporte");
        assertThat(JsonPath.<String>read(json,
                "$.paths['/api/v1/catalogos/clientes'].post.requestBody.content['application/json'].schema['$ref']"))
                .endsWith("/ClienteRequest");
    }
}
