-- =====================================================================
-- Migración de datos: Banco (bancos, cuentas, transacciones y conciliaciones)
-- Requiere 01 ejecutado. Ejecutar:  psql -v cia=1 -f 04_banco.sql
--
-- Códigos legacy de transaccionesbancarias.TipoTransaccion (proc. Balance_CuentaBanco):
--   D depósito (+)   C nota de crédito (+)   T transferencia recibida (+)   I balance inicial (+)
--   K cheque (-)     B cargo / nota de débito (-)   E transferencia enviada (-)
-- Estado: 'T' en tránsito (sin conciliar), 'C' conciliado (FechaConciliada); cualquier otro = anulado.
-- Las transacciones 'I' se suman al saldo inicial de la cuenta.
-- =====================================================================
\if :{?cia} \else \set cia 1 \endif
BEGIN;

-- ---------------------------------------------------------------- Bancos
INSERT INTO banco (id, nombre, estado, actualizado_por)
SELECT b.idbanco,
       left(upper(coalesce(nullif(trim(b.nombre), ''), 'BANCO ' || b.idbanco)), 60)
         || CASE WHEN count(*) OVER (PARTITION BY upper(trim(b.nombre))) > 1 THEN ' ' || b.idbanco ELSE '' END,
       CASE WHEN b.estado IN ('I', 'i') THEN 'I' ELSE 'A' END, 'migracion'
FROM legacy.banco b;

-- ---------------------------------------------------------------- Cuentas
CREATE TEMP TABLE tmp_tx AS
SELECT t.*, cb.iddetallebanco AS cuenta_id
FROM legacy.transaccionesbancarias t
JOIN legacy.detallebanco cb ON cb.idbanco = t.idbanco AND trim(cb.numerocuenta) = trim(t.cuentabanco);

INSERT INTO cuenta_bancaria (id, compania_id, banco_id, numero, nombre, tipo, moneda, saldo_inicial, fecha_saldo_inicial,
                             cuenta_contable, proximo_cheque, estado, actualizado_por)
SELECT d.iddetallebanco, coalesce(d.idcompania, :cia), d.idbanco,
       left(coalesce(nullif(trim(d.numerocuenta), ''), 'SIN-NUMERO-' || d.iddetallebanco), 30),
       left(coalesce(nullif(nullif(trim(d.nombrecuenta), ''), 'NA'), trim(d.numerocuenta), 'Cuenta ' || d.iddetallebanco), 60),
       CASE WHEN upper(d.tipocuenta) LIKE 'AHO%' THEN 'AHORRO' ELSE 'CORRIENTE' END,
       CASE WHEN upper(d.moneda) IN ('USD', 'US$', 'DOLAR', 'DOLARES') THEN 'USD' ELSE 'DOP' END,
       round(coalesce(d.balanceinicial, 0)::numeric, 2) + coalesce(ini.monto, 0),
       coalesce(f.primera, date '2000-01-01'),
       left(nullif(trim(d.ctacontable), ''), 12),
       greatest(coalesce(d.nocheque, 0), coalesce(chq.ultimo, 0)) + 1,
       CASE WHEN d.estado IN ('I', 'i') THEN 'I' ELSE 'A' END, 'migracion'
FROM legacy.detallebanco d
JOIN banco b ON b.id = d.idbanco
LEFT JOIN (SELECT cuenta_id, round(sum(monto)::numeric, 2) monto FROM tmp_tx
            WHERE tipotransaccion = 'I' AND estado IN ('C', 'T') GROUP BY 1) ini ON ini.cuenta_id = d.iddetallebanco
LEFT JOIN (SELECT cuenta_id, min(fecha) primera FROM tmp_tx WHERE fecha IS NOT NULL GROUP BY 1) f ON f.cuenta_id = d.iddetallebanco
LEFT JOIN (SELECT cuenta_id, max(referencia) ultimo FROM tmp_tx WHERE tipotransaccion = 'K' GROUP BY 1) chq ON chq.cuenta_id = d.iddetallebanco;

-- ---------------------------------------------------------------- Conciliaciones
-- Una por (cuenta, FechaConciliada). El saldo del banco se toma de legacy.conciliacionbancaria si existe;
-- si no, se asume igual al saldo en libros de lo conciliado (sin partidas en tránsito registradas).
INSERT INTO conciliacion_bancaria (compania_id, cuenta_id, fecha_corte, saldo_banco, saldo_libros, observacion)
SELECT c.compania_id, x.cuenta_id, x.fechaconciliada,
       coalesce(round(cl.balance::numeric, 2), libros.saldo), libros.saldo, 'Migrada del sistema anterior'
FROM (SELECT DISTINCT cuenta_id, fechaconciliada FROM tmp_tx
       WHERE estado = 'C' AND fechaconciliada IS NOT NULL AND tipotransaccion <> 'I') x
JOIN cuenta_bancaria c ON c.id = x.cuenta_id
CROSS JOIN LATERAL (
  SELECT c.saldo_inicial + coalesce(sum(CASE WHEN t.tipotransaccion IN ('D', 'C', 'T') THEN t.monto ELSE -t.monto END), 0)::numeric(18,2) saldo
    FROM tmp_tx t
   WHERE t.cuenta_id = x.cuenta_id AND t.estado = 'C' AND t.tipotransaccion <> 'I' AND t.fechaconciliada <= x.fechaconciliada) libros
LEFT JOIN LATERAL (
  SELECT l.balance FROM legacy.conciliacionbancaria l
   WHERE l.idbanco = c.banco_id AND trim(l.numerocuenta) = c.numero AND l.fecha = x.fechaconciliada
   ORDER BY l.idconciliacionbancaria DESC LIMIT 1) cl ON true;

-- ---------------------------------------------------------------- Movimientos
-- Número de cheque: 0 = sin número; si se repite en la cuenta se agrega un sufijo (-2, -3...) para respetar el índice único.
INSERT INTO movimiento_bancario (id, compania_id, cuenta_id, numero, fecha, tipo, referencia, beneficiario, concepto, monto,
                                 estado, conciliacion_id, origen_tipo, usuario_id, anulado_en, motivo_anulacion, actualizado_por)
SELECT t.idtransaccionesbancarias, c.compania_id, t.cuenta_id, t.idtransaccionesbancarias,
       coalesce(t.fecha, c.fecha_saldo_inicial),
       CASE t.tipotransaccion WHEN 'D' THEN 'DEPOSITO' WHEN 'C' THEN 'NOTA_CREDITO' WHEN 'T' THEN 'TRANSFERENCIA_ENTRADA'
                              WHEN 'K' THEN 'CHEQUE' WHEN 'B' THEN 'NOTA_DEBITO' WHEN 'E' THEN 'TRANSFERENCIA_SALIDA' END,
       CASE WHEN coalesce(t.referencia, 0) = 0 THEN NULL
            WHEN t.rn > 1 AND t.tipotransaccion = 'K' THEN t.referencia || '-' || t.rn ELSE t.referencia::text END,
       left(nullif(trim(t.beneficiario), ''), 100),
       left(coalesce(nullif(trim(t.concepto), ''), 'Migrado'), 200),
       round(t.monto::numeric, 2),
       CASE WHEN t.estado IN ('C', 'T') THEN 'REGISTRADO' ELSE 'ANULADO' END,
       CASE WHEN t.estado = 'C' THEN cc.id END,
       'MANUAL', u.id,
       CASE WHEN t.estado NOT IN ('C', 'T') OR t.estado IS NULL THEN now() END,
       CASE WHEN t.estado NOT IN ('C', 'T') OR t.estado IS NULL THEN 'Anulado en el sistema anterior' END,
       'migracion'
FROM (SELECT x.*, row_number() OVER (PARTITION BY x.cuenta_id, x.tipotransaccion, x.referencia ORDER BY x.idtransaccionesbancarias) rn
        FROM tmp_tx x) t
JOIN cuenta_bancaria c ON c.id = t.cuenta_id
LEFT JOIN conciliacion_bancaria cc ON cc.cuenta_id = t.cuenta_id AND cc.fecha_corte = t.fechaconciliada
LEFT JOIN usuario u ON lower(u.username) = lower(t.usuario)
WHERE t.tipotransaccion IN ('D', 'C', 'T', 'K', 'B', 'E') AND coalesce(t.monto, 0) > 0;

-- ---------------------------------------------------------------- Contadores y secuencias
INSERT INTO contador_documento (compania_id, clave, ultimo)
SELECT :cia, 'MOV_BANCO', coalesce(max(numero), 0) FROM movimiento_bancario WHERE compania_id = :cia
ON CONFLICT (compania_id, clave) DO UPDATE SET ultimo = excluded.ultimo;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['banco','cuenta_bancaria','conciliacion_bancaria','movimiento_bancario'] LOOP
    EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), coalesce((SELECT max(id) FROM %I), 1))', t, t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- Control
-- 1) Transacciones legacy que no se migraron (cuenta inexistente, tipo desconocido o monto en cero)
SELECT t.idtransaccionesbancarias, t.idbanco, t.cuentabanco, t.tipotransaccion, t.monto
FROM legacy.transaccionesbancarias t
WHERE NOT EXISTS (SELECT 1 FROM movimiento_bancario m WHERE m.id = t.idtransaccionesbancarias)
  AND t.tipotransaccion <> 'I'
LIMIT 50;

-- 2) Saldo por cuenta: comparar con el reporte Balance_CuentaBanco del sistema anterior
SELECT c.id, b.nombre banco, c.numero, c.saldo_inicial,
       c.saldo_inicial + coalesce(sum(CASE WHEN m.tipo IN ('DEPOSITO', 'TRANSFERENCIA_ENTRADA', 'NOTA_CREDITO') THEN m.monto ELSE -m.monto END)
                                  FILTER (WHERE m.estado = 'REGISTRADO'), 0) saldo_libros
FROM cuenta_bancaria c JOIN banco b ON b.id = c.banco_id
LEFT JOIN movimiento_bancario m ON m.cuenta_id = c.id
GROUP BY c.id, b.nombre, c.numero, c.saldo_inicial ORDER BY c.id;

COMMIT;
