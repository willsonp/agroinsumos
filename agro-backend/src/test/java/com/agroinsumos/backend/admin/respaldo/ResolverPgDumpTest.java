package com.agroinsumos.backend.admin.respaldo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** PG_DUMP puede ser el programa, la carpeta bin (con o sin barra final) o venir con comillas. */
class ResolverPgDumpTest {

    @Test
    void carpetaConPgDumpExeAgregaElPrograma(@TempDir Path bin) throws Exception {
        Files.createFile(bin.resolve("pg_dump.exe"));
        assertThat(RespaldoService.resolverPgDump(bin.toString())).isEqualTo(bin.resolve("pg_dump.exe").toString());
        assertThat(RespaldoService.resolverPgDump(bin + "/")).isEqualTo(bin.resolve("pg_dump.exe").toString());
        assertThat(RespaldoService.resolverPgDump("\"" + bin + "\"")).isEqualTo(bin.resolve("pg_dump.exe").toString());
    }

    @Test
    void carpetaSinExeUsaPgDump(@TempDir Path bin) {
        assertThat(RespaldoService.resolverPgDump(bin.toString())).isEqualTo(bin.resolve("pg_dump").toString());
    }

    @Test
    void programaONombreSeRespetan(@TempDir Path bin) throws Exception {
        Path exe = Files.createFile(bin.resolve("pg_dump.exe"));
        assertThat(RespaldoService.resolverPgDump(exe.toString())).isEqualTo(exe.toString());
        assertThat(RespaldoService.resolverPgDump("pg_dump")).isEqualTo("pg_dump");
        assertThat(RespaldoService.resolverPgDump("  ")).isEqualTo("pg_dump");
        assertThat(RespaldoService.resolverPgDump(null)).isEqualTo("pg_dump");
    }
}
