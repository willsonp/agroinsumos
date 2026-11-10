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

-- ---------------------------------------------------------------- Contadores y secuencias
INSERT INTO contador_documento (compania_id, clave, ultimo)
VALUES (:cia, 'RECIBO', (SELECT coalesce(max(numero), 0) FROM recibo WHERE compania_id = :cia)),
       (:cia, 'COMPRA', (SELECT coalesce(max(numero), 0) FROM compra WHERE compania_id = :cia))
ON CONFLICT (compania_id, clave) DO UPDATE SET ultimo = excluded.ultimo;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['recibo','recibo_aplicacion','recibo_pago','compra','compra_linea'] LOOP
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

COMMIT;
