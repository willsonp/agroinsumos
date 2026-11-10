package com.agroinsumos.backend.ecf;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class EcfConfig {
    /** Fechas fiscales en hora de República Dominicana (UTC-4), independientemente del servidor. */
    public static final ZoneId ZONA_RD = ZoneId.of("America/Santo_Domingo");

    @Bean
    Clock clock() {
        return Clock.system(ZONA_RD);
    }
}
