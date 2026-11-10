-- =====================================================================
-- Migración de datos: Cuentas por Cobrar (recibos históricos) y Compras / CxP
-- Requiere 01 y 02 ejecutados. Ejecutar:  psql -v cia=1 -f 03_caja_cxc_compras.sql
-- No se migran las sesiones de caja históricas: el sistema nuevo inicia con cajas cerradas.
-- =====================================================================
\if :{?cia} \else \set cia 1 \endif
BEGIN;

-- ---------------------------------------------------------------- Recibos históricos
-- Necesarios para que el estado de cuenta (facturas - recibos) cuadre con factura.balance.
INSERT INTO recibo (id, compania_id, numero, fecha, cliente_id, concepto, total_aplicado, total_retenciones,
                    total_recibido, devuelta, estado, usuario_id, actualizado_por)
SELECT r.norecibo, :cia, r.norecibo, coalesce(r.fecha, r.fechaactualizacion::date, date '2000-01-01'), r.idcliente,
       left(concat_ws(' ', r.descripcion, r.descripcion2), 200),
       coalesce(d.aplicado, 0), coalesce(d.retenciones, 0),
       coalesce(r.pagoefectivo, 0) + coalesce(r.pagotarjeta, 0) + coalesce(r.pagocheque, 0),
       greatest(coalesce(r.pagoefectivo, 0) + coalesce(r.pagotarjeta, 0) + coalesce(r.pagocheque, 0)
                - coalesce(d.aplicado, 0) - coalesce(d.retenciones, 0), 0),
       CASE WHEN r.estado = 'A' THEN 'EMITIDO' ELSE 'ANULADO' END, u.id, 'migracion'
FROM legacy.reciboingreso r
JOIN cliente c ON c.id = r.idcliente
LEFT JOIN usuario u ON u.id = r.iduser
LEFT JOIN (SELECT d.norecibo, sum(d.valorpagado) aplicado,
                  sum(coalesce(d.retencionitbis, 0) + coalesce(d.retencionisr, 0)) retenciones
             FROM legacy.detalle_recibo d JOIN factura f ON f.id = d.nofactura GROUP BY 1) d ON d.norecibo = r.norecibo;

INSERT INTO recibo_aplicacion (recibo_id, documento_tipo, documento_id, monto, retencion_itbis, retencion_isr)
SELECT d.norecibo, 'FACTURA', d.nofactura, greatest(coalesce(d.valorpagado, 0), 0),
       greatest(coalesce(d.retencionitbis, 0), 0), greatest(coalesce(d.retencionisr, 0), 0)
FROM legacy.detalle_recibo d
JOIN recibo r ON r.id = d.norecibo
JOIN factura f ON f.id = d.nofactura
WHERE coalesce(d.valorpagado, 0) + coalesce(d.retencionitbis, 0) + coalesce(d.retencionisr, 0) > 0;

INSERT INTO recibo_pago (recibo_id, forma_pago, monto)
SELECT r.norecibo, x.forma, x.monto
FROM legacy.reciboingreso r
JOIN recibo n ON n.id = r.norecibo
CROSS JOIN LATERAL (VALUES ('EFECTIVO', r.pagoefectivo), ('TARJETA', r.pagotarjeta), ('CHEQUE', r.pagocheque)) x(forma, monto)
WHERE coalesce(x.monto, 0) > 0;

-- Pagos sin recibo en el sistema anterior (ventas de contado cobradas en caja, ajustes de balance): en el sistema
-- nuevo toda factura se salda con un recibo, así que se crea uno por la diferencia (total - balance - recibos).
-- Sin esto, el estado de cuenta mostraría las ventas de contado históricas como deuda del cliente.
CREATE TEMP TABLE tmp_pago_caja AS
SELECT f.id factura_id, f.cliente_id, f.fecha, f.usuario_id, f.total - f.balance - coalesce(a.aplicado, 0) monto,
       lf.pagoefectivo, lf.pagotarjeta, lf.pagocheque,
       row_number() OVER (ORDER BY f.fecha, f.id) + (SELECT coalesce(max(id), 0) FROM recibo) nuevo_id,
       row_number() OVER (ORDER BY f.fecha, f.id) + (SELECT coalesce(max(numero), 0) FROM recibo WHERE compania_id = :cia) nuevo_numero
FROM factura f
JOIN legacy.factura lf ON lf.nofactura = f.id
LEFT JOIN (SELECT a.documento_id, sum(a.monto + a.retencion_itbis + a.retencion_isr) aplicado
             FROM recibo_aplicacion a JOIN recibo r ON r.id = a.recibo_id
            WHERE r.estado = 'EMITIDO' AND a.documento_tipo = 'FACTURA' GROUP BY 1) a ON a.documento_id = f.id
WHERE f.compania_id = :cia AND f.estado <> 'ANULADA' AND f.total - f.balance - coalesce(a.aplicado, 0) > 0.009;

INSERT INTO recibo (id, compania_id, numero, fecha, cliente_id, concepto, total_aplicado, total_retenciones,
                    total_recibido, devuelta, estado, usuario_id, actualizado_por)
SELECT t.nuevo_id, :cia, t.nuevo_numero, t.fecha, t.cliente_id, 'Pago registrado en el sistema anterior (factura ' || t.factura_id || ')',
       t.monto, 0, t.monto, 0, 'EMITIDO', t.usuario_id, 'migracion'
FROM tmp_pago_caja t;

INSERT INTO recibo_aplicacion (recibo_id, documento_tipo, documento_id, monto, retencion_itbis, retencion_isr)
SELECT t.nuevo_id, 'FACTURA', t.factura_id, t.monto, 0, 0 FROM tmp_pago_caja t;

-- Forma de pago: la registrada en la factura si cuadra con el monto; si no, efectivo
INSERT INTO recibo_pago (recibo_id, forma_pago, monto)
SELECT t.nuevo_id, x.forma, round(x.monto::numeric, 2)
FROM tmp_pago_caja t
CROSS JOIN LATERAL (VALUES ('EFECTIVO', t.pagoefectivo), ('TARJETA', t.pagotarjeta), ('CHEQUE', t.pagocheque)) x(forma, monto)
WHERE coalesce(x.monto, 0) > 0
  AND abs(coalesce(t.pagoefectivo, 0) + coalesce(t.pagotarjeta, 0) + coalesce(t.pagocheque, 0) - t.monto) <= 0.05
UNION ALL
SELECT t.nuevo_id, 'EFECTIVO', t.monto
FROM tmp_pago_caja t
WHERE abs(coalesce(t.pagoefectivo, 0) + coalesce(t.pagotarjeta, 0) + coalesce(t.pagocheque, 0) - t.monto) > 0.05;

-- ---------------------------------------------------------------- Compras (CxP)
WITH ncf AS (
  SELECT c.idcompra,
         CASE WHEN row_number() OVER (PARTITION BY c.suplidor, nullif(trim(c.ncf), '') ORDER BY c.idcompra) = 1
               AND c.estado = 'A' THEN left(nullif(trim(c.ncf), ''), 19) END AS ncf
  FROM legacy.compra c
), lineas AS (
  SELECT idcompra, sum(coalesce(itbis_pagado, 0))::numeric itbis, sum(coalesce(descuento, 0))::numeric descuento
  FROM legacy.deta_compra GROUP BY 1
), cxp AS (
  SELECT nodocumento, sum(balance) balance FROM legacy.facturacreditocxp WHERE estado = 'A' GROUP BY 1
)
INSERT INTO compra (id, compania_id, numero, proveedor_id, fecha, ncf_proveedor, condicion, dias_credito,
                    fecha_vencimiento, almacen_id, numero_orden, comentario, subtotal, total_descuento, total_itbis,
                    flete, total, itbis_retenido, isr_retenido, balance, estado, actualizado_por)
SELECT c.idcompra, :cia, c.idcompra, c.suplidor, coalesce(c.fecha, date '2000-01-01'), n.ncf,
       CASE WHEN upper(c.condicion) IN ('CR', 'C', 'E') THEN 'CREDITO' ELSE 'CONTADO' END,
       greatest(coalesce(c.dias, 0), 0),
       coalesce(c.fechavence, coalesce(c.fecha, date '2000-01-01') + greatest(coalesce(c.dias, 0), 0)),
       (SELECT id FROM almacen WHERE compania_id = :cia AND principal), c.ordennum::text, left(c.comentario, 300),
       greatest(round(coalesce(c.monto, 0)::numeric - coalesce(l.itbis, 0) - coalesce(c.flete, 0)::numeric, 2), 0),
       coalesce(l.descuento, 0), coalesce(l.itbis, 0), coalesce(c.flete, 0)::numeric,
       round(coalesce(c.monto, 0)::numeric, 2),
       least(coalesce(c.itbisretenido, 0), coalesce(l.itbis, 0)), coalesce(c.montoretencionisr, 0),
       CASE WHEN c.estado <> 'A' THEN 0
            ELSE least(greatest(coalesce(x.balance, c.balance::numeric, 0), 0),
                       greatest(round(coalesce(c.monto, 0)::numeric, 2) - least(coalesce(c.itbisretenido, 0), coalesce(l.itbis, 0))
                                - coalesce(c.montoretencionisr, 0), 0)) END,
       CASE WHEN c.estado <> 'A' THEN 'ANULADA'
            WHEN coalesce(x.balance, c.balance::numeric, 0) <= 0 THEN 'PAGADA' ELSE 'REGISTRADA' END,
       'migracion'
FROM legacy.compra c
JOIN proveedor p ON p.id = c.suplidor
JOIN ncf n ON n.idcompra = c.idcompra
LEFT JOIN lineas l ON l.idcompra = c.idcompra
LEFT JOIN cxp x ON x.nodocumento = c.idcompra;

INSERT INTO compra_linea (compra_id, linea, articulo_id, cantidad, costo_unitario, descuento, tasa_itbis, importe, itbis, costo_final)
SELECT d.idcompra, row_number() OVER (PARTITION BY d.idcompra ORDER BY d.iddeta_compra), d.articulo, d.qty::numeric,
       coalesce(d.precio, 0)::numeric, coalesce(d.descuento, 0)::numeric,
       CASE WHEN d.porcientoitbis IN (0, 16, 18) THEN d.porcientoitbis::numeric ELSE 18 END,
       round((d.qty * coalesce(d.precio, 0) - coalesce(d.descuento, 0))::numeric, 2), coalesce(d.itbis_pagado, 0)::numeric,
       coalesce(d.precio, 0)::numeric
FROM legacy.deta_compra d
JOIN compra c ON c.id = d.idcompra
JOIN articulo a ON a.id = d.articulo
WHERE d.qty > 0;

-- Pagos a suplidores del sistema anterior: no se migran uno a uno; se registra un pago por compra por lo ya pagado
-- (total - retenciones - balance). Así el estado de cuenta del suplidor cuadra con el balance de sus compras.
-- Llevan la fecha de la compra (anterior al inicio contable): no generan asientos ni movimientos de banco/caja.
CREATE TEMP TABLE tmp_pago_prov AS
SELECT c.id compra_id, c.proveedor_id, c.fecha, c.total - c.itbis_retenido - c.isr_retenido - c.balance monto,
       row_number() OVER (ORDER BY c.fecha, c.id) + (SELECT coalesce(max(id), 0) FROM pago_proveedor) nuevo_id,
       row_number() OVER (ORDER BY c.fecha, c.id) + (SELECT coalesce(max(numero), 0) FROM pago_proveedor WHERE compania_id = :cia) nuevo_numero
FROM compra c
WHERE c.compania_id = :cia AND c.estado <> 'ANULADA' AND c.actualizado_por = 'migracion'
  AND c.total - c.itbis_retenido - c.isr_retenido - c.balance > 0.009;

INSERT INTO pago_proveedor (id, compania_id, numero, fecha, proveedor_id, concepto, total, estado, actualizado_por)
SELECT t.nuevo_id, :cia, t.nuevo_numero, t.fecha, t.proveedor_id, 'Pagado en el sistema anterior (compra ' || t.compra_id || ')',
       t.monto, 'EMITIDO', 'migracion'
FROM tmp_pago_prov t;

INSERT INTO pago_proveedor_aplicacion (pago_id, compra_id, monto, monto_dop)
SELECT t.nuevo_id, t.compra_id, t.monto, t.monto FROM tmp_pago_prov t;

INSERT INTO pago_proveedor_forma (pago_id, forma_pago, monto, referencia)
SELECT t.nuevo_id, 'TRANSFERENCIA', t.monto, 'SISTEMA ANTERIOR' FROM tmp_pago_prov t;

-- ---------------------------------------------------------------- Contadores y secuencias
INSERT INTO contador_documento (compania_id, clave, ultimo)
VALUES (:cia, 'RECIBO', (SELECT coalesce(max(numero), 0) FROM recibo WHERE compania_id = :cia)),
       (:cia, 'COMPRA', (SELECT coalesce(max(numero), 0) FROM compra WHERE compania_id = :cia)),
       (:cia, 'PAGO_PROVEEDOR', (SELECT coalesce(max(numero), 0) FROM pago_proveedor WHERE compania_id = :cia))
ON CONFLICT (compania_id, clave) DO UPDATE SET ultimo = excluded.ultimo;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['recibo','recibo_aplicacion','recibo_pago','compra','compra_linea','pago_proveedor','pago_proveedor_aplicacion','pago_proveedor_forma'] LOOP
    EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), coalesce((SELECT max(id) FROM %I), 1))', t, t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- Control
-- 1) Clientes cuyo balance de facturas NO cuadra con (facturas - recibos - notas de crédito):
--    revisar antes de salir en vivo (recibos legacy borrados o editados a mano).
SELECT c.id, c.nombre, b.balance_facturas, e.estado_cuenta
FROM cliente c
JOIN (SELECT cliente_id, sum(balance) balance_facturas FROM factura WHERE estado <> 'ANULADA' GROUP BY 1) b ON b.cliente_id = c.id
JOIN (SELECT f.cliente_id, sum(f.total) - coalesce(sum(r.abonado), 0) estado_cuenta
        FROM factura f
        LEFT JOIN (SELECT a.documento_id, sum(a.monto + a.retencion_itbis + a.retencion_isr) abonado
                     FROM recibo_aplicacion a JOIN recibo r ON r.id = a.recibo_id
                    WHERE r.estado = 'EMITIDO' AND a.documento_tipo = 'FACTURA' GROUP BY 1) r ON r.documento_id = f.id
       WHERE f.estado <> 'ANULADA' GROUP BY 1) e ON e.cliente_id = c.id
WHERE abs(b.balance_facturas - e.estado_cuenta) > 0.01
ORDER BY abs(b.balance_facturas - e.estado_cuenta) DESC
LIMIT 50;

-- 2) Notas de débito legacy pendientes (no referencian factura; registrarlas manualmente como e-CF 33 si aplica)
SELECT count(*) AS notas_debito_legacy_pendientes, coalesce(sum(balance), 0) AS monto
FROM legacy.notadebito WHERE estado = 'A' AND balance > 0;

-- 3) CxP: legacy vs nuevo
SELECT (SELECT round(sum(balance)::numeric, 2) FROM legacy.facturacreditocxp WHERE estado = 'A') AS cxp_legacy,
       (SELECT sum(balance) FROM compra WHERE estado <> 'ANULADA') AS cxp_nuevo;

-- 4) Suplidores cuyo balance de compras NO cuadra con (compras - pagos): revisar antes de salir en vivo
SELECT p.id, p.nombre, b.balance_compras, e.estado_cuenta
FROM proveedor p
JOIN (SELECT proveedor_id, sum(balance) balance_compras FROM compra WHERE estado <> 'ANULADA' GROUP BY 1) b ON b.proveedor_id = p.id
JOIN (SELECT c.proveedor_id, sum(c.total - c.itbis_retenido - c.isr_retenido) - coalesce(sum(a.pagado), 0) estado_cuenta
        FROM compra c
        LEFT JOIN (SELECT a.compra_id, sum(a.monto_dop) pagado FROM pago_proveedor_aplicacion a
                     JOIN pago_proveedor pp ON pp.id = a.pago_id WHERE pp.estado = 'EMITIDO' GROUP BY 1) a ON a.compra_id = c.id
       WHERE c.estado <> 'ANULADA' GROUP BY 1) e ON e.proveedor_id = p.id
WHERE abs(b.balance_compras - e.estado_cuenta) > 0.01;

COMMIT;
