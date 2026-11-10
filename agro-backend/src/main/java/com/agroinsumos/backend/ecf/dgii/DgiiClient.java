package com.agroinsumos.backend.ecf.dgii;

import java.util.List;

/** Puerto hacia los servicios web de la DGII (permite simularlo en pruebas). */
public interface DgiiClient {

    record Mensaje(String valor, Integer codigo) {}

    record RecepcionResultado(String trackId, String error, String mensaje) {}

    record EstadoResultado(String trackId, Integer codigo, String estado, String rnc, String encf,
                           Boolean secuenciaUtilizada, String fechaRecepcion, List<Mensaje> mensajes) {}

    record RfceResultado(Integer codigo, String estado, List<Mensaje> mensajes, String encf,
                         Boolean secuenciaUtilizada) {}

    RecepcionResultado enviarEcf(Long companiaId, String nombreArchivo, String xmlFirmado);

    EstadoResultado consultarEstado(Long companiaId, String trackId);

    RfceResultado enviarRfce(Long companiaId, String nombreArchivo, String xmlFirmado);
}
