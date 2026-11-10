-- =====================================================================
-- Migración de datos: conduces en tránsito, ventas financiadas y apartados
-- Requiere 01..03 ejecutados (clientes, artículos, facturas). Ejecutar:  psql -v cia=1 -f 07_conduces_cuotas_apartados.sql
--
-- Conduces: facturapro con estado 'T' (en tránsito). La existencia migrada ya refleja la salida, así que se crean
--           ABIERTOS sin mover inventario; se facturan desde la pantalla Conduces.
-- Cuotas:   un plan por factura a crédito con cuotas pendientes. El interés del legado ya estaba dentro del balance de
--           la factura, por lo que el plan se crea con interés 0 y solo con las cuotas pendientes (monto = su balance).
--           Si la suma de cuotas pendientes no coincide con el balance de la factura, no se migra y se lista al final.
-- Apartados: el legado guardaba solo el nombre (sin cliente), no se pueden migrar automáticamente: se listan los
--           que tienen abonos para registrarlos a mano (o devolverlos).
-- =====================================================================
\if :{?cia} \else \set cia 1 \endif
BEGIN;

-- ---------------------------------------------------------------- Conduces en tránsito
INSERT INTO conduce (id, compania_id, numero, fecha, cliente_id, almacen_id, vendedor_id, tipo_precio, comentario,
                     total_estimado, estado, actualizado_por)
SELECT fp.nofactura, :cia, fp.nofactura, coalesce(fp.fecha, current_date), c.id,
       coalesce((SELECT min(d.idalmacen) FROM legacy.detalle_facturapro d WHERE d.numerofactura = fp.nofactura),
                (SELECT id FROM almacen WHERE compania_id = :cia AND principal LIMIT 1)),
       (SELECT v.id FROM vendedor v WHERE v.id = fp.idvendedor), c.tipo_precio,
       left(concat_ws(' · ', 'Migrado del sistema anterior', nullif(trim(fp.comentario), '')), 300),
       round(coalesce(fp.monto, 0)::numeric, 2), 'ABIERTO', 'migracion'
FROM legacy.facturapro fp
JOIN cliente c ON c.id = fp.idcliente
WHERE upper(fp.estado) = 'T'
  AND EXISTS (SELECT 1 FROM legacy.detalle_facturapro d JOIN articulo a ON a.id = d.idarticulo WHERE d.numerofactura = fp.nofactura);

INSERT INTO conduce_linea (conduce_id, linea, articulo_id, cantidad, precio_unitario, descuento_porcentaje, costo_unitario)
SELECT d.numerofactura, row_number() OVER (PARTITION BY d.numerofactura ORDER BY d.iddetallefactura), d.idarticulo,
       d.cantidad, round(coalesce(d.precio, 0)::numeric, 4),
       CASE WHEN coalesce(d.precio, 0) * d.cantidad > 0
            THEN least(round((coalesce(d.descuento, 0) / (d.precio * d.cantidad) * 100)::numeric, 2), 100) ELSE 0 END,
       round(coalesce(d.costodeventa, 0)::numeric, 4)
FROM legacy.detalle_facturapro d
JOIN conduce c ON c.id = d.numerofactura
JOIN articulo a ON a.id = d.idarticulo
WHERE coalesce(d.cantidad, 0) > 0 AND upper(coalesce(d.estado, 'A')) <> 'N';

-- ---------------------------------------------------------------- Ventas financiadas (cuotas pendientes)
CREATE TEMP TABLE tmp_plan AS
SELECT f.id factura_id, f.cliente_id, f.total, f.balance, count(*) cuotas, sum(round(cu.balance::numeric, 2)) pendiente,
       min(cu.fechavencimiento) primera, max(coalesce(cu.mora, 0)) mora
FROM legacy.cuotas cu
JOIN factura f ON f.id = cu.nofactura AND f.cliente_id = cu.idcliente
WHERE upper(coalesce(cu.estado, 'A')) = 'A' AND coalesce(cu.balance, 0) > 0
  AND f.compania_id = :cia AND f.estado = 'EMITIDA' AND f.condicion = 'CREDITO' AND f.balance > 0
GROUP BY f.id, f.cliente_id, f.total, f.balance;

INSERT INTO plan_financiamiento (compania_id, numero, fecha, factura_id, cliente_id, pagado_previo, capital, tasa_interes,
                                 interes, cantidad_cuotas, frecuencia_dias, mora_porcentaje, dias_gracia, estado)
SELECT :cia, row_number() OVER (ORDER BY t.factura_id), current_date, t.factura_id, t.cliente_id, t.total - t.balance, t.balance,
       0, 0, t.cuotas::int, 30, least(t.mora, 100), 5, 'ACTIVO'
FROM tmp_plan t
WHERE abs(t.pendiente - t.balance) <= 0.05 AND t.cuotas <= 120;

INSERT INTO plan_cuota (plan_id, numero, vencimiento, monto)
SELECT p.id, row_number() OVER (PARTITION BY p.id ORDER BY cu.fechavencimiento, cu.nocuotas),
       coalesce(cu.fechavencimiento, current_date), round(cu.balance::numeric, 2)
FROM plan_financiamiento p
JOIN legacy.cuotas cu ON cu.nofactura = p.factura_id AND upper(coalesce(cu.estado, 'A')) = 'A' AND coalesce(cu.balance, 0) > 0
WHERE p.compania_id = :cia AND p.creado_en >= now() - interval '1 minute';

-- Ajuste de redondeo: la última cuota cuadra con el balance de la factura
UPDATE plan_cuota q SET monto = q.monto + (p.capital - s.suma)
FROM plan_financiamiento p, (SELECT plan_id, sum(monto) suma, max(numero) ultima FROM plan_cuota GROUP BY plan_id) s
WHERE q.plan_id = p.id AND s.plan_id = p.id AND q.numero = s.ultima AND p.capital <> s.suma AND p.compania_id = :cia;

-- La factura vence con su última cuota
UPDATE factura f SET fecha_vencimiento = s.ultima
FROM plan_financiamiento p, (SELECT plan_id, max(vencimiento) ultima FROM plan_cuota GROUP BY plan_id) s
WHERE p.factura_id = f.id AND s.plan_id = p.id AND p.compania_id = :cia;

-- ---------------------------------------------------------------- Contadores y secuencias
INSERT INTO contador_documento (compania_id, clave, ultimo)
VALUES (:cia, 'CONDUCE', (SELECT coalesce(max(numero), 0) FROM conduce WHERE compania_id = :cia)),
       (:cia, 'FINANCIAMIENTO', (SELECT coalesce(max(numero), 0) FROM plan_financiamiento WHERE compania_id = :cia))
ON CONFLICT (compania_id, clave) DO UPDATE SET ultimo = greatest(contador_documento.ultimo, excluded.ultimo);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['conduce','conduce_linea','plan_financiamiento','plan_cuota'] LOOP
    EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), coalesce((SELECT max(id) FROM %I), 1))', t, t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- Control
-- 1) Facturas con cuotas pendientes que NO se migraron (las cuotas no cuadran con el balance de la factura)
SELECT t.factura_id, t.cliente_id, t.balance balance_factura, t.pendiente cuotas_pendientes, t.cuotas
FROM tmp_plan t
WHERE NOT EXISTS (SELECT 1 FROM plan_financiamiento p WHERE p.factura_id = t.factura_id);

-- 2) Apartados del legado con dinero abonado (registrarlos en Apartados o devolverlos)
SELECT a.noapartado, a.fecha, a.nombre, a.concepto, round(a.monto::numeric, 2) monto, round(a.balance::numeric, 2) balance
FROM legacy.apartado a
WHERE upper(coalesce(a.estado, 'A')) = 'A' AND coalesce(a.monto, 0) > 0
ORDER BY a.fecha;

-- 3) Conduces en tránsito del legado no migrados (cliente o artículos inexistentes)
SELECT fp.nofactura, fp.fecha, fp.idcliente, fp.nombrecli, fp.monto
FROM legacy.facturapro fp
WHERE upper(fp.estado) = 'T' AND NOT EXISTS (SELECT 1 FROM conduce c WHERE c.id = fp.nofactura);

COMMIT;
