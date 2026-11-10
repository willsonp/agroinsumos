-- =====================================================================
-- Migración de datos MySQL (pv_agroinsumos) -> PostgreSQL: módulo Seguridad
--
-- Paso previo: copiar las tablas legacy TAL CUAL a un esquema "legacy" en PostgreSQL,
-- por ejemplo con pgloader:
--     pgloader mysql://root:***@host/pv_agroinsumos postgresql://agro:***@host/agrodb
--     (con: ALTER SCHEMA 'pv_agroinsumos' RENAME TO 'legacy', y "downcase identifiers")
-- Luego ejecutar este script UNA vez, después de que Flyway haya creado el esquema nuevo.
-- =====================================================================
BEGIN;

-- 0) Verificación: el sistema nuevo exige username único (el legacy no lo validaba)
DO $$
DECLARE dup text;
BEGIN
  SELECT string_agg(u, ', ') INTO dup
  FROM (SELECT lower(trim(username)) AS u FROM legacy.usuario GROUP BY 1 HAVING count(*) > 1) d;
  IF dup IS NOT NULL THEN
    RAISE EXCEPTION 'Usernames duplicados en legacy.usuario: %. Corríjalos antes de migrar.', dup;
  END IF;
END $$;

-- 1) Compañías (se conservan los IDs para no romper referencias)
INSERT INTO compania (id, nombre, direccion, rnc, correo, representante, sitio_web, ruta_imagen,
                      itbis, afp, ars, arl, infotep, acepta_contabilidad,
                      cuenta_caja_general, cuenta_itbis_retenido, cuenta_isr, cuenta_devolucion,
                      cuenta_itbis_adelantado, cuenta_descuento_venta, cuenta_itbis_venta, cuenta_ingresos,
                      estado, actualizado_en, actualizado_por)
SELECT idcompania, coalesce(nullif(trim(nombre), ''), 'SIN NOMBRE'), direccion,
       coalesce(nullif(regexp_replace(rnc, '\D', '', 'g'), ''), lpad(idcompania::text, 9, '0')),
       correo, representante, sitioweb, rutaimagen,
       coalesce(itbis, 18), coalesce(afp, 0), coalesce(ars, 0), coalesce(arl, 0), coalesce(infotep, 0),
       upper(coalesce(aceptacontabilidad, 'N')) IN ('S', 'Y', '1'),
       cuentacajageneral, itbisretenido, isr, cuentadevolucion, itbisadelantadocompra, descuentoenventa,
       itbisenventa, cuentaingresos,
       CASE WHEN estado = 'A' THEN 'A' ELSE 'I' END, coalesce(actualizacion, now()), 'migracion'
FROM legacy.compania;

-- 2) Usuarios: el hash SHA-1(password + idUsuario) se conserva con el prefijo {sha1legacy}.
--    En el primer login correcto Spring Security lo re-codifica a BCrypt automáticamente.
INSERT INTO usuario (id, compania_id, cedula, nombre, apellidos, direccion, username, password_hash,
                     nivel, sexo, correo, ruta_imagen, turno_id, estado, actualizado_en, actualizado_por)
SELECT u.idusuario, coalesce(u.idcompania, 1), nullif(regexp_replace(u.cedula, '\D', '', 'g'), ''),
       coalesce(nullif(trim(u.nombre), ''), trim(u.username)), u.apellidos, u.direccion, trim(u.username),
       CASE WHEN nullif(trim(u.userpassw), '') IS NULL THEN NULL
            ELSE '{sha1legacy}' || u.idusuario || '$' || lower(trim(u.userpassw)) END,
       CASE WHEN upper(u.nivel) IN ('A','S','U','O') THEN upper(u.nivel) ELSE 'U' END,
       CASE WHEN upper(u.sexo) IN ('M','F') THEN upper(u.sexo) END,
       nullif(lower(trim(u.correo)), ''), u.rutaimagen, coalesce(u.turno, 1),
       CASE WHEN u.estado = 'A' THEN 'A' ELSE 'I' END, coalesce(u.actualizacion, now()), 'migracion'
FROM legacy.usuario u;

-- Correos duplicados: se conserva el primero (el índice único lo exige)
-- (si falla el INSERT por uk_usuario_correo, limpie legacy.usuario.correo antes)

-- 3) Menú: el catálogo de opciones es nuevo (V8__menu_opciones.sql, códigos del frontend web).
--    Los nombres de componentes Swing (Obj_Contenedor / Nombre_ObjHijo) no tienen equivalente,
--    por lo que los permisos de legacy.detalle_opciones_menu deben reasignarse desde
--    "Administración > Usuarios y permisos". Los administradores (nivel A) ven todo.

-- 4) Reajustar las secuencias IDENTITY tras insertar IDs explícitos
SELECT setval(pg_get_serial_sequence('compania', 'id'), coalesce(max(id), 1)) FROM compania;
SELECT setval(pg_get_serial_sequence('usuario', 'id'), coalesce(max(id), 1)) FROM usuario;

COMMIT;
