-- =====================================================================
-- Migración de datos: Caja chica
-- Requiere 01 y 05 ejecutados. Ejecutar:  psql -v cia=1 -v inicio="'2026-12-01'" -f 08_caja_chica.sql
--
-- Legacy: cajachica (vales: Fecha, Concepto, Monto, Estado) y cajachicabalance (cuadres: MontoCaja = fondo,
--         Balance = efectivo que quedó, TotalEgresos). El legado no guardaba RNC/NCF ni a qué reposición pertenece
--         cada vale, así que se migra el ESTADO ACTUAL del fondo, no su historia:
--   * un fondo "Caja chica" con el monto del último cuadre;
--   * una APERTURA por ese monto y, si falta dinero, un vale "Vales pendientes del sistema anterior" por la diferencia,
--     pendiente de reponer (la primera reposición en el sistema nuevo deja el fondo completo).
-- Ambos movimientos llevan fecha anterior a :inicio, por lo que NO generan asientos: el saldo de la cuenta de caja chica
-- viene en la contabilidad migrada (05). Cotejar el saldo con el último cuadre del sistema anterior (control al final).
-- =====================================================================
\if :{?cia} \else \set cia 1 \endif
\if :{?inicio} \else \set inicio current_date \endif
BEGIN;

CREATE TEMP TABLE tmp_cuadre AS
SELECT round(coalesce(b.montocaja, 0)::numeric, 2) fondo, round(greatest(coalesce(b.balance, 0), 0)::numeric, 2) saldo, b.fecha
FROM legacy.cajachicabalance b
WHERE coalesce(b.montocaja, 0) > 0
ORDER BY b.fecha DESC NULLS LAST, b.idcajachicabalance DESC LIMIT 1;

-- Vales registrados después del último cuadre (aún no repuestos)
CREATE TEMP TABLE tmp_vales AS
SELECT coalesce(round(sum(c.monto)::numeric, 2), 0) monto
FROM legacy.cajachica c, tmp_cuadre t
WHERE upper(coalesce(c.estado, 'A')) = 'A' AND coalesce(c.monto, 0) > 0 AND (t.fecha IS NULL OR c.fecha > t.fecha);

INSERT INTO caja_chica (compania_id, nombre, monto_fondo, saldo, estado, actualizado_por)
SELECT :cia, 'Caja chica', t.fondo, greatest(least(t.saldo - v.monto, t.fondo), 0), 'A', 'migracion'
FROM tmp_cuadre t, tmp_vales v
WHERE NOT EXISTS (SELECT 1 FROM caja_chica WHERE compania_id = :cia AND nombre = 'Caja chica');

INSERT INTO caja_chica_movimiento (compania_id, caja_chica_id, numero, fecha, tipo, concepto, monto)
SELECT :cia, f.id, coalesce((SELECT max(numero) FROM caja_chica_movimiento WHERE compania_id = :cia), 0) + 1,
       (:inicio)::date - 1, 'APERTURA', 'Fondo migrado del sistema anterior', f.monto_fondo
FROM caja_chica f WHERE f.compania_id = :cia AND f.actualizado_por = 'migracion'
  AND NOT EXISTS (SELECT 1 FROM caja_chica_movimiento m WHERE m.caja_chica_id = f.id);

INSERT INTO caja_chica_movimiento (compania_id, caja_chica_id, numero, fecha, tipo, concepto, monto, tipo_bien_servicio)
SELECT :cia, f.id, (SELECT max(numero) FROM caja_chica_movimiento WHERE compania_id = :cia) + 1,
       (:inicio)::date - 1, 'GASTO', 'Vales pendientes del sistema anterior', f.monto_fondo - f.saldo, '02'
FROM caja_chica f WHERE f.compania_id = :cia AND f.actualizado_por = 'migracion' AND f.monto_fondo > f.saldo
  AND NOT EXISTS (SELECT 1 FROM caja_chica_movimiento m WHERE m.caja_chica_id = f.id AND m.tipo = 'GASTO');

-- ---------------------------------------------------------------- Contadores
INSERT INTO contador_documento (compania_id, clave, ultimo)
SELECT :cia, 'CAJA_CHICA', coalesce(max(numero), 0) FROM caja_chica_movimiento WHERE compania_id = :cia
ON CONFLICT (compania_id, clave) DO UPDATE SET ultimo = greatest(contador_documento.ultimo, excluded.ultimo);

-- ---------------------------------------------------------------- Control
-- 1) Fondo migrado: comparar con el último cuadre (FrmCuadreCajaChica) del sistema anterior
SELECT f.nombre, f.monto_fondo, f.saldo, f.monto_fondo - f.saldo por_reponer, t.fecha ultimo_cuadre, t.saldo saldo_cuadre, v.monto vales_posteriores
FROM caja_chica f, tmp_cuadre t, tmp_vales v WHERE f.compania_id = :cia;

-- 2) Saldo contable de la cuenta de caja chica según la contabilidad migrada (debe coincidir con el fondo − lo por reponer).
--    Si sale "NO EXISTE", cree la cuenta en el catálogo o asigne la clave CAJA_CHICA a una cuenta existente
--    (Contabilidad > Catálogo > Cuentas por defecto); sin ella no se contabilizan los vales ni las reposiciones.
SELECT x.codigo, coalesce(c.nombre, 'NO EXISTE EN EL CATÁLOGO') nombre,
       coalesce((SELECT sum(l.debito - l.credito) FROM asiento_linea l JOIN asiento a ON a.id = l.asiento_id
                  WHERE l.cuenta_id = c.id AND a.estado = 'REGISTRADO'), 0) saldo_contable
FROM (SELECT coalesce((SELECT codigo FROM cuenta_contable_config WHERE compania_id = :cia AND clave = 'CAJA_CHICA'), '110103') codigo) x
LEFT JOIN cuenta_contable c ON c.compania_id = :cia AND c.codigo = x.codigo;

COMMIT;
