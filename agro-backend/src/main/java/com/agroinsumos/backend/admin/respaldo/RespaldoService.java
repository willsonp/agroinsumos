package com.agroinsumos.backend.admin.respaldo;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Respaldo completo (estructura y datos) de la base de datos en un archivo .sql con pg_dump.
 * Como en el sistema anterior, el archivo se llama BK_&lt;base&gt;_&lt;día del mes&gt;.sql: se conservan hasta 31 respaldos y
 * cada día sobrescribe el del mismo día del mes anterior. Se restaura con: psql -d &lt;base&gt; -f BK_&lt;base&gt;_NN.sql
 */
@Slf4j
@Service
public class RespaldoService {

    public static final String PARAM_DIRECTORIO = "RESPALDO_DIRECTORIO";
    private static final Pattern NOMBRE = Pattern.compile("BK_[A-Za-z0-9_]+_\\d{2}\\.sql");
    private static final Pattern URL = Pattern.compile("jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?]+).*");

    public record Archivo(String nombre, long bytes, Instant modificado) {}

    public record Estado(String directorio, String baseDatos, String proximoNombre, boolean diarioAutomatico, boolean respaldoDeHoy,
                         List<Archivo> respaldos) {}

    public record Resultado(String nombre, String ruta, long bytes, long milisegundos) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String host, puerto, base, usuario, clave, pgDump, directorioPorDefecto, cron;
    private final long tiempoMaximoMin;
    private final ReentrantLock enCurso = new ReentrantLock();

    public RespaldoService(JdbcTemplate jdbc, Clock clock,
                           @Value("${spring.datasource.url}") String url,
                           @Value("${spring.datasource.username}") String usuario,
                           @Value("${spring.datasource.password}") String clave,
                           @Value("${app.respaldo.pg-dump:pg_dump}") String pgDump,
                           @Value("${app.respaldo.directorio:${user.home}/agro-respaldos}") String directorio,
                           @Value("${app.respaldo.cron:0 15 * * * *}") String cron,
                           @Value("${app.respaldo.tiempo-maximo-min:30}") long tiempoMaximoMin) {
        this.jdbc = jdbc;
        this.clock = clock;
        Matcher m = URL.matcher(url);
        if (!m.matches()) throw new IllegalStateException("URL de base de datos no soportada para respaldos: " + url);
        this.host = m.group(1);
        this.puerto = m.group(2) == null ? "5432" : m.group(2);
        this.base = m.group(3);
        this.usuario = usuario;
        this.clave = clave;
        this.pgDump = pgDump;
        this.directorioPorDefecto = directorio;
        this.cron = cron;
        this.tiempoMaximoMin = tiempoMaximoMin;
    }

    public Path directorio() {
        String valor = jdbc.query("select valor from parametro_sistema where clave = ?", rs -> rs.next() ? rs.getString(1) : null, PARAM_DIRECTORIO);
        return Path.of(valor != null ? valor : directorioPorDefecto).toAbsolutePath().normalize();
    }

    public String nombreDeHoy() {
        return "BK_" + base.replaceAll("[^A-Za-z0-9_]", "_") + "_" + String.format("%02d", LocalDate.now(clock).getDayOfMonth()) + ".sql";
    }

    public Estado estado() {
        return new Estado(directorio().toString(), base, nombreDeHoy(), !"-".equals(cron), hechoHoy(), listar());
    }

    /** ¿Ya existe el respaldo de hoy (archivo del día modificado hoy)? */
    public boolean hechoHoy() {
        Path p = directorio().resolve(nombreDeHoy());
        try {
            return Files.isRegularFile(p) && LocalDate.ofInstant(Files.getLastModifiedTime(p).toInstant(), clock.getZone())
                    .equals(LocalDate.now(clock));
        } catch (IOException e) {
            return false;
        }
    }

    public List<Archivo> listar() {
        Path dir = directorio();
        if (!Files.isDirectory(dir)) return List.of();
        List<Archivo> res = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> NOMBRE.matcher(p.getFileName().toString()).matches()).forEach(p -> {
                try {
                    res.add(new Archivo(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toInstant()));
                } catch (IOException ignorado) { /* archivo borrado mientras se listaba */ }
            });
        } catch (IOException e) {
            throw new BusinessException("DIRECTORIO_ILEGIBLE", "No se puede leer " + dir + ": " + e.getMessage());
        }
        res.sort(Comparator.comparing(Archivo::modificado).reversed());
        return res;
    }

    /** Ejecuta pg_dump (texto plano, con DROP ... IF EXISTS para poder restaurar sobre una base existente). */
    public Resultado crear() {
        if (!enCurso.tryLock()) throw new BusinessException("RESPALDO_EN_CURSO", "Ya se está generando un respaldo");
        try {
            Path dir = directorio();
            Files.createDirectories(dir);
            Path destino = dir.resolve(nombreDeHoy());
            Path temporal = dir.resolve(destino.getFileName() + ".tmp");
            Path errores = Files.createTempFile("pg_dump", ".log");
            long inicio = System.currentTimeMillis();
            var pb = new ProcessBuilder(pgDump, "-h", host, "-p", puerto, "-U", usuario, "-d", base, "--clean", "--if-exists",
                    "--no-owner", "--no-privileges", "--encoding=UTF8", "-f", temporal.toString());
            pb.environment().put("PGPASSWORD", clave);
            pb.redirectErrorStream(false).redirectError(errores.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (!p.waitFor(tiempoMaximoMin, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                Files.deleteIfExists(temporal);
                throw new BusinessException("RESPALDO_FALLIDO", "pg_dump excedió " + tiempoMaximoMin + " minutos");
            }
            String salida = Files.readString(errores, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(errores);
            if (p.exitValue() != 0) {
                Files.deleteIfExists(temporal);
                if (salida.contains("server version mismatch")) {
                    // pg_dump debe ser de la misma versión mayor del servidor o más nueva (p.ej. PostgreSQL 18 requiere pg_dump 18)
                    throw new BusinessException("PG_DUMP_VERSION", "La versión de pg_dump es más antigua que la del servidor PostgreSQL. "
                            + "Instale el cliente de la misma versión del servidor y configure la variable PG_DUMP con su ruta "
                            + "(p.ej. C:\\Program Files\\PostgreSQL\\18\\bin\\pg_dump.exe o /usr/lib/postgresql/18/bin/pg_dump). Detalle: "
                            + (salida.length() > 200 ? salida.substring(0, 200) : salida));
                }
                throw new BusinessException("RESPALDO_FALLIDO", "pg_dump terminó con error: " + (salida.length() > 300 ? salida.substring(0, 300) : salida));
            }
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            var r = new Resultado(destino.getFileName().toString(), destino.toString(), Files.size(destino), System.currentTimeMillis() - inicio);
            log.info("Respaldo generado: {} ({} bytes en {} ms)", r.ruta(), r.bytes(), r.milisegundos());
            return r;
        } catch (IOException e) {
            throw new BusinessException("RESPALDO_FALLIDO", "No se pudo generar el respaldo: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("RESPALDO_FALLIDO", "Respaldo interrumpido");
        } finally {
            enCurso.unlock();
        }
    }

    /**
     * Respaldo DIARIO automático: cada hora (minuto 15) verifica si ya existe el respaldo de hoy y, si falta, lo genera.
     * Así no se pierde el día aunque el servidor haya estado apagado a una hora fija. app.respaldo.cron="-" lo desactiva.
     */
    @Scheduled(cron = "${app.respaldo.cron:0 15 * * * *}", zone = "America/Santo_Domingo")
    public void programado() {
        if (hechoHoy()) return;
        try {
            crear();
        } catch (RuntimeException e) {
            log.error("Falló el respaldo diario automático: {}", e.getMessage());
        }
    }

    public Path archivo(String nombre) {
        if (!NOMBRE.matcher(nombre).matches()) throw new BusinessException("NOMBRE_INVALIDO", "Nombre de respaldo inválido");
        Path p = directorio().resolve(nombre);
        if (!Files.isRegularFile(p)) throw new NotFoundException("Respaldo", nombre);
        return p;
    }

    /** Carpeta del servidor donde se guardan los respaldos (debe ser absoluta y con permiso de escritura). */
    public Estado configurarDirectorio(String ruta, String usuarioApp) {
        if (ruta == null || ruta.isBlank()) throw new BusinessException("DIRECTORIO_INVALIDO", "Indique la carpeta");
        Path p;
        try {
            p = Path.of(ruta.trim());
        } catch (InvalidPathException e) {
            throw new BusinessException("DIRECTORIO_INVALIDO", "Ruta inválida: " + ruta);
        }
        if (!p.isAbsolute()) throw new BusinessException("DIRECTORIO_INVALIDO", "Use una ruta absoluta (p.ej. /respaldos o D:\\Respaldos)");
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new BusinessException("DIRECTORIO_INVALIDO", "No se pudo crear " + p + ": " + e.getMessage());
        }
        if (!Files.isWritable(p)) throw new BusinessException("DIRECTORIO_INVALIDO", "Sin permiso de escritura en " + p);
        jdbc.update("""
                insert into parametro_sistema (clave, valor, actualizado_por) values (?, ?, ?)
                on conflict (clave) do update set valor = excluded.valor, actualizado_por = excluded.actualizado_por, actualizado_en = now()""",
                PARAM_DIRECTORIO, p.toAbsolutePath().normalize().toString(), usuarioApp);
        return estado();
    }
}
