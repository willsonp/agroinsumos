-- =====================================================================
-- Migración de datos: Contabilidad (catálogo, cuentas por defecto y asientos históricos)
-- Requiere 01..04 ejecutados.
-- Ejecutar:  psql -v cia=1 -v inicio="'2026-12-01'" -f 05_contabilidad.sql
--   inicio = fecha de arranque en el sistema nuevo. Los documentos con fecha anterior NO se contabilizan
--            (su contabilidad es la que se migra aquí); desde esa fecha los asientos los genera el sistema.
--
-- Legacy:
--   catalogo (Cuenta, Nombre, AceptaMov S/N, Origen D/C, AcumulaCuenta = cuenta de grupo, Nivel)
--   entradadiario / detalle_entradadiario (asientos manuales: Cuenta, Origen D/C, Monto, ClienteSup)
--   transaccionesconta / detalle_transaccionconta (asientos de documentos: CuentaContable, DebitoCredito, Valor)
-- El tipo de cuenta se deduce del primer dígito (1 activo, 2 pasivo, 3 capital, 4 ingreso, 5 costo, 6+ gasto).
-- Solo se migran asientos balanceados; los descuadrados se listan al final para corregirlos a mano.
-- =====================================================================
\if :{?cia} \else \set cia 1 \endif
\if :{?inicio} \else \set inicio current_date \endif
BEGIN;

-- ---------------------------------------------------------------- Catálogo
INSERT INTO cuenta_contable (compania_id, codigo, nombre, tipo, nivel, acepta_movimiento, estado, actualizado_por)
SELECT DISTINCT ON (trim(c.cuenta)) :cia, trim(c.cuenta), left(coalesce(nullif(trim(c.nombre), ''), trim(c.cuenta)), 100),
       CASE left(trim(c.cuenta), 1) WHEN '1' THEN 'ACTIVO' WHEN '2' THEN 'PASIVO' WHEN '3' THEN 'CAPITAL'
                                    WHEN '4' THEN 'INGRESO' WHEN '5' THEN 'COSTO' ELSE 'GASTO' END,
       greatest(least(coalesce(c.nivel, 1), 10), 1),
       upper(coalesce(c.aceptamov, 'S')) = 'S', 'A', 'migracion'
FROM legacy.catalogo c
WHERE coalesce(c.idcompania, :cia) = :cia AND nullif(trim(c.cuenta), '') IS NOT NULL
ORDER BY trim(c.cuenta), c.secuencia DESC;

UPDATE cuenta_contable h SET padre_id = p.id
FROM legacy.catalogo c JOIN cuenta_contable p ON p.compania_id = :cia AND p.codigo = trim(c.acumulacuenta)
WHERE h.compania_id = :cia AND h.codigo = trim(c.cuenta) AND trim(c.acumulacuenta) <> trim(c.cuenta);

-- Nivel según la jerarquía real
WITH RECURSIVE arbol AS (
  SELECT id, 1 AS nivel FROM cuenta_contable WHERE compania_id = :cia AND padre_id IS NULL
  UNION ALL
  SELECT h.id, a.nivel + 1 FROM cuenta_contable h JOIN arbol a ON h.padre_id = a.id WHERE a.nivel < 10
)
UPDATE cuenta_contable c SET nivel = a.nivel FROM arbol a WHERE a.id = c.id;

-- ---------------------------------------------------------------- Cuentas por defecto y fecha de inicio
INSERT INTO cuenta_contable_config (compania_id, clave, codigo)
SELECT c.id, x.clave, trim(x.codigo) FROM compania c
CROSS JOIN LATERAL (VALUES ('CAJA', c.cuenta_caja_general), ('ITBIS_RETENIDO_POR_CLIENTES', c.cuenta_itbis_retenido),
                           ('ISR_RETENIDO_POR_CLIENTES', c.cuenta_isr), ('DEVOLUCIONES_VENTAS', c.cuenta_devolucion),
                           ('ITBIS_ADELANTADO', c.cuenta_itbis_adelantado), ('ITBIS_POR_PAGAR', c.cuenta_itbis_venta),
                           ('INGRESOS_VENTAS', c.cuenta_ingresos), ('DESCUENTOS_VENTAS', c.cuenta_descuento_venta)) AS x(clave, codigo)
WHERE c.id = :cia AND nullif(trim(x.codigo), '') IS NOT NULL
ON CONFLICT (compania_id, clave) DO UPDATE SET codigo = excluded.codigo;

UPDATE compania SET fecha_inicio_contable = :inicio WHERE id = :cia;

-- ---------------------------------------------------------------- Asientos manuales (entradadiario)
CREATE TEMP TABLE tmp_asiento (origen text, legacy_id int, fecha date, concepto text, anulado boolean, total numeric);
CREATE TEMP TABLE tmp_linea (origen text, legacy_id int, cuenta text, debito numeric, credito numeric, tercero int);

INSERT INTO tmp_asiento
SELECT 'ED', e.identradadiario, coalesce(e.fecha, date '2000-01-01'), left(coalesce(nullif(trim(e.concepto), ''), 'Entrada de diario'), 200),
       upper(coalesce(e.estado, 'A')) NOT IN ('A', 'C'), 0
FROM legacy.entradadiario e;

INSERT INTO tmp_linea
SELECT 'ED', d.identradadiario, trim(d.cuenta),
       CASE WHEN upper(d.origen) = 'D' THEN round(d.monto::numeric, 2) ELSE 0 END,
       CASE WHEN upper(d.origen) = 'C' THEN round(d.monto::numeric, 2) ELSE 0 END,
       nullif(d.clientesup, 0)
FROM legacy.detalle_entradadiario d
WHERE coalesce(d.monto, 0) > 0 AND upper(coalesce(d.estado, 'A')) <> 'N';

-- ---------------------------------------------------------------- Asientos de documentos (transaccionesconta)
INSERT INTO tmp_asiento
SELECT 'TC', t.secuencia, coalesce(t.fecha, date '2000-01-01'),
       left(concat_ws(' ', 'Sistema anterior', t.tipo, t.documento, '-', nullif(trim(t.descripcion), '')), 200),
       upper(coalesce(t.estado, 'A')) NOT IN ('A', 'C'), 0
FROM legacy.transaccionesconta t;

INSERT INTO tmp_linea
SELECT 'TC', d.numerotransaccion, trim(d.cuentacontable),
       CASE WHEN upper(d.debitocredito) = 'D' THEN round(d.valor::numeric, 2) ELSE 0 END,
       CASE WHEN upper(d.debitocredito) = 'C' THEN round(d.valor::numeric, 2) ELSE 0 END,
       NULL
FROM legacy.detalle_transaccionconta d
WHERE coalesce(d.valor, 0) > 0;

-- Asientos que se pueden migrar: balanceados, con al menos 2 líneas y todas sus cuentas en el catálogo
CREATE TEMP TABLE tmp_valido AS
SELECT a.origen, a.legacy_id
FROM tmp_asiento a JOIN tmp_linea l ON l.origen = a.origen AND l.legacy_id = a.legacy_id
LEFT JOIN cuenta_contable c ON c.compania_id = :cia AND c.codigo = l.cuenta
GROUP BY a.origen, a.legacy_id
HAVING count(*) >= 2 AND sum(l.debito) = sum(l.credito) AND sum(l.debito) > 0 AND bool_and(c.id IS NOT NULL);

UPDATE tmp_asiento a SET total = s.total
FROM (SELECT origen, legacy_id, sum(debito) total FROM tmp_linea GROUP BY 1, 2) s
WHERE s.origen = a.origen AND s.legacy_id = a.legacy_id;

CREATE TEMP TABLE tmp_map AS
SELECT a.*, row_number() OVER (ORDER BY a.fecha, a.origen, a.legacy_id)
            + coalesce((SELECT max(numero) FROM asiento WHERE compania_id = :cia), 0) AS numero
FROM tmp_asiento a JOIN tmp_valido v USING (origen, legacy_id);

INSERT INTO asiento (compania_id, numero, fecha, tipo, concepto, total, estado, motivo_anulacion)
SELECT :cia, m.numero, m.fecha, CASE WHEN m.origen = 'ED' THEN 'MANUAL' ELSE 'AUTOMATICO' END, m.concepto, m.total,
       CASE WHEN m.anulado THEN 'ANULADO' ELSE 'REGISTRADO' END,
       CASE WHEN m.anulado THEN 'Anulado en el sistema anterior' END
FROM tmp_map m;

INSERT INTO asiento_linea (asiento_id, linea, cuenta_id, debito, credito, tercero_id)
SELECT a.id, row_number() OVER (PARTITION BY a.id ORDER BY l.debito DESC, l.cuenta), c.id,
       CASE WHEN l.debito > 0 THEN l.debito ELSE 0 END, CASE WHEN l.debito > 0 THEN 0 ELSE l.credito END, l.tercero
FROM tmp_map m
JOIN asiento a ON a.compania_id = :cia AND a.numero = m.numero
JOIN tmp_linea l ON l.origen = m.origen AND l.legacy_id = m.legacy_id
JOIN cuenta_contable c ON c.compania_id = :cia AND c.codigo = l.cuenta
WHERE l.debito > 0 OR l.credito > 0;

-- Las cuentas de grupo con movimientos migrados quedan como de detalle (revisar el catálogo)
UPDATE cuenta_contable c SET acepta_movimiento = TRUE
WHERE c.compania_id = :cia AND NOT c.acepta_movimiento AND EXISTS (SELECT 1 FROM asiento_linea l WHERE l.cuenta_id = c.id);

-- ---------------------------------------------------------------- Contadores
INSERT INTO contador_documento (compania_id, clave, ultimo)
SELECT :cia, 'ASIENTO', coalesce(max(numero), 0) FROM asiento WHERE compania_id = :cia
ON CONFLICT (compania_id, clave) DO UPDATE SET ultimo = excluded.ultimo;

-- ---------------------------------------------------------------- Control
-- 1) Asientos del sistema anterior que NO se migraron (descuadrados o con cuentas inexistentes)
SELECT a.origen, a.legacy_id, a.fecha, a.concepto, sum(l.debito) debitos, sum(l.credito) creditos,
       string_agg(DISTINCT l.cuenta, ', ') FILTER (WHERE c.id IS NULL) cuentas_inexistentes
FROM tmp_asiento a
LEFT JOIN tmp_linea l ON l.origen = a.origen AND l.legacy_id = a.legacy_id
LEFT JOIN cuenta_contable c ON c.compania_id = :cia AND c.codigo = l.cuenta
WHERE NOT EXISTS (SELECT 1 FROM tmp_valido v WHERE v.origen = a.origen AND v.legacy_id = a.legacy_id)
GROUP BY a.origen, a.legacy_id, a.fecha, a.concepto
ORDER BY a.fecha LIMIT 100;

-- 2) Cuentas referenciadas por departamentos, clientes, proveedores, bancos o la configuración que no existen en el catálogo
SELECT 'departamento' fuente, d.nombre, x.codigo FROM departamento d
CROSS JOIN LATERAL (VALUES (d.cuenta_ingreso), (d.cuenta_costo), (d.cuenta_inventario)) x(codigo)
WHERE nullif(trim(x.codigo), '') IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cuenta_contable c WHERE c.compania_id = :cia AND c.codigo = trim(x.codigo))
UNION ALL
SELECT 'cuenta bancaria', b.nombre, b.cuenta_contable FROM cuenta_bancaria b
WHERE nullif(trim(b.cuenta_contable), '') IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cuenta_contable c WHERE c.compania_id = :cia AND c.codigo = trim(b.cuenta_contable))
UNION ALL
SELECT 'configuración', k.clave, k.codigo FROM cuenta_contable_config k
WHERE k.compania_id = :cia AND NOT EXISTS (SELECT 1 FROM cuenta_contable c WHERE c.compania_id = :cia AND c.codigo = k.codigo);

-- 3) Balanza migrada (debe cuadrar)
SELECT sum(l.debito) debitos, sum(l.credito) creditos
FROM asiento_linea l JOIN asiento a ON a.id = l.asiento_id WHERE a.compania_id = :cia AND a.estado = 'REGISTRADO';

COMMIT;
