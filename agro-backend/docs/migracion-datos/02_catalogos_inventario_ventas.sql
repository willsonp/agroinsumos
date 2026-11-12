-- =====================================================================
-- Migración de datos: Catálogos, Inventario y Facturas (histórico)
-- Requiere: 01_seguridad.sql ejecutado y el esquema "legacy" cargado con pgloader.
-- Compañía destino: :cia (por defecto 1). Ejecutar:  psql -v cia=1 -f 02_...sql
-- =====================================================================
\if :{?cia} \else \set cia 1 \endif
BEGIN;

-- ---------------------------------------------------------------- Catálogos globales
INSERT INTO zona (id, nombre)
SELECT idzona, coalesce(nullif(trim(nombre), ''), 'ZONA ' || idzona) FROM legacy.zona;

INSERT INTO ciudad (id, nombre, estado, actualizado_por)
SELECT idciudad, trim(nombreciudad), CASE WHEN coalesce(estado, 'A') = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM legacy.ciudad;

INSERT INTO unidad (id, nombre, estado, actualizado_por)
SELECT DISTINCT ON (lower(trim(nombre))) idunidad, trim(nombre), CASE WHEN estado = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM legacy.unidad ORDER BY lower(trim(nombre)), idunidad;
-- Unidades duplicadas por nombre: se redirigen a la primera
CREATE TEMP TABLE map_unidad AS
SELECT u.idunidad AS legacy_id, n.id AS nuevo_id
FROM legacy.unidad u JOIN unidad n ON lower(n.nombre) = lower(trim(u.nombre));

INSERT INTO turno (id, nombre, estado, actualizado_por)
SELECT idturno, nombre, CASE WHEN estado = 'A' THEN 'A' ELSE 'I' END, 'migracion' FROM legacy.turno
ON CONFLICT (id) DO UPDATE SET nombre = excluded.nombre, estado = excluded.estado;

-- ---------------------------------------------------------------- Catálogos por compañía
INSERT INTO departamento (id, compania_id, nombre, tipo, cuenta_costo, cuenta_ingreso, cuenta_inventario, estado, actualizado_por)
SELECT DISTINCT ON (iddepartamento) iddepartamento, :cia, trim(nombre),
       CASE WHEN tipodepartamento IN ('I','S','E') THEN tipodepartamento ELSE 'I' END,
       cuentacosto, cuentaingreso, cuentainventario, CASE WHEN estado = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM legacy.departamento ORDER BY iddepartamento;

INSERT INTO almacen (id, compania_id, nombre, ubicacion, principal, estado, actualizado_por)
SELECT idalmacen, :cia, coalesce(nullif(trim(nombre), ''), 'ALMACEN ' || idalmacen), nullif(ubicacion, 'NA'),
       idalmacen = (SELECT min(idalmacen) FROM legacy.almacen), CASE WHEN estado = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM legacy.almacen;

-- Vendedor: en el legacy idvendedor = idusuario (call agrega_vendedor)
INSERT INTO vendedor (id, compania_id, usuario_id, nombre, rnc_cedula, fecha_nacimiento, comision, ciudad_id,
                      direccion, correo, telefono, estado, actualizado_por)
SELECT v.idvendedor, :cia, u.id, trim(v.nombre),
       CASE WHEN regexp_replace(v.rnccedula, '\D', '', 'g') ~ '^(\d{9}|\d{11})$' THEN regexp_replace(v.rnccedula, '\D', '', 'g') END,
       v.fechanac, least(greatest(coalesce(v.comision, 0), 0), 100), c.id,
       nullif(concat_ws(' ', v.calle, v.apartamento), ''), nullif(v.email, ''), nullif(v.contacto, ''),
       CASE WHEN v.estado = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM legacy.vendedor v
LEFT JOIN usuario u ON u.id = v.idvendedor
LEFT JOIN ciudad c ON c.id = v.ciudad;

-- Clientes: la tabla real del legacy es "clientex" (la tabla "cliente" está en desuso)
WITH base AS (
  SELECT x.*, nullif(regexp_replace(coalesce(x.rnccedula, ''), '\D', '', 'g'), '') AS rnc_limpio
  FROM legacy.clientex x
), valido AS (
  SELECT b.*, CASE WHEN rnc_limpio ~ '^(\d{9}|\d{11})$'
                    AND row_number() OVER (PARTITION BY rnc_limpio ORDER BY idcliente) = 1
               THEN rnc_limpio END AS rnc_ok
  FROM base b
)
INSERT INTO cliente (id, compania_id, nombre, rnc_cedula, tipo_ecf_preferido, exento_itbis, tipo_precio, limite_credito,
                     ciudad_id, zona_id, direccion, correo, contacto, cuenta_contable, estado, actualizado_por)
SELECT v.idcliente, :cia, upper(trim(v.nombre)), v.rnc_ok,
       -- NCF legacy -> e-CF: 01 crédito fiscal -> 31, 14 régimen especial -> 44, 15 gubernamental -> 45, resto -> 32
       CASE WHEN v.rnc_ok IS NULL THEN 32
            WHEN v.tiponcf = '01' THEN 31 WHEN v.tiponcf = '14' THEN 44 WHEN v.tiponcf = '15' THEN 45 ELSE 32 END,
       upper(coalesce(v.libreimpuesto, 'N')) IN ('S', 'Y', '1'),
       -- REVISAR con negocio: letra de lista de precios legacy
       CASE upper(coalesce(v.tipoprecio, 'A')) WHEN 'C' THEN 'CREDITO' WHEN 'M' THEN 'MAYOR' ELSE 'CONTADO' END,
       greatest(coalesce(v.limitecredito, 0), 0)::numeric(18,2),
       c.id, z.id, nullif(concat_ws(' ', v.calle, v.apartamento), ''), nullif(v.email, ''), left(v.contacto, 100),
       v.cuentacontable, CASE WHEN v.estado = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM valido v
LEFT JOIN ciudad c ON c.id = v.ciudad
LEFT JOIN zona z ON z.id = nullif(v.zona, 0);

-- Teléfonos de clientes (deta_localizador tipo_entidad = 'C')
UPDATE cliente c SET telefono = t.numero
FROM (SELECT DISTINCT ON (identidad) identidad, numero FROM legacy.deta_localizador
      WHERE tipo_entidad = 'C' AND estado = 'A' ORDER BY identidad, iddeta_localizador) t
WHERE t.identidad = c.id;

INSERT INTO proveedor (id, compania_id, nombre, rnc_cedula, direccion, sector, ciudad_id, correo, contacto,
                       cuenta_contable, estado, actualizado_por)
SELECT p.idproveedor, :cia, upper(trim(p.nombre)),
       CASE WHEN regexp_replace(p.rnc, '\D', '', 'g') ~ '^(\d{9}|\d{11})$'
             AND row_number() OVER (PARTITION BY regexp_replace(p.rnc, '\D', '', 'g') ORDER BY p.idproveedor) = 1
            THEN regexp_replace(p.rnc, '\D', '', 'g') END,
       p.calle, p.sector, c.id, nullif(p.correo, ''), p.contacto, p.cuentacontable,
       CASE WHEN p.estado = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM legacy.proveedor p LEFT JOIN ciudad c ON c.id = p.ciudad;

-- ---------------------------------------------------------------- Artículos
-- Los precios legacy INCLUYEN ITBIS (FrmFactura: base = precio / 1.18) -> se guardan sin ITBIS con 4 decimales
INSERT INTO articulo (id, compania_id, codigo_barra, referencia, nombre, tipo, departamento_id, unidad_id, proveedor_id,
                      indicador_itbis, costo, costo_promedio, precio_contado, precio_credito, precio_mayor,
                      descuento_maximo, ubicacion, imagen_url, estado, actualizado_por)
SELECT a.idarticulo, :cia,
       CASE WHEN row_number() OVER (PARTITION BY nullif(trim(a.codigobarra), '') ORDER BY a.idarticulo) = 1
            THEN nullif(trim(a.codigobarra), '') END,
       nullif(trim(a.referencia), ''), left(trim(a.nombre), 80),
       CASE WHEN a.tipo = 'I' THEN 'B' ELSE 'S' END,
       a.departamento, coalesce(mu.nuevo_id, (SELECT min(id) FROM unidad)), pr.id,
       x.indicador, coalesce(a.costo, 0), coalesce(a.costo, 0),
       round(coalesce(a.precioalcontado, 0)::numeric / x.factor, 4),
       round(coalesce(a.precioacredito, 0)::numeric / x.factor, 4),
       round(coalesce(a.precioalmayor, 0)::numeric / x.factor, 4),
       least(greatest(coalesce(a.descuento, 0), 0), 100),
       nullif(concat_ws('-', nullif(a.tramo, 'NA'), nullif(a.ubicacion, 'NA')), ''), a.imgpathweb,
       CASE WHEN a.estado = 'A' THEN 'A' ELSE 'I' END, 'migracion'
FROM legacy.articulo a
CROSS JOIN LATERAL (SELECT
       CASE WHEN upper(coalesce(a.pagaitebis, 'S')) = 'N' THEN 4
            WHEN a.impuesto = 16 THEN 2 WHEN a.impuesto = 0 THEN 3 ELSE 1 END AS indicador,
       CASE WHEN upper(coalesce(a.pagaitebis, 'S')) = 'N' OR a.impuesto = 0 THEN 1.0
            WHEN a.impuesto = 16 THEN 1.16 ELSE 1.18 END AS factor) x
LEFT JOIN map_unidad mu ON mu.legacy_id = a.unidad
LEFT JOIN proveedor pr ON pr.id = a.proveedor
WHERE a.departamento IN (SELECT id FROM departamento);

-- ---------------------------------------------------------------- Existencias iniciales
-- Misma fórmula del proc. ConsultaCantidad_Articulo, calculada UNA vez y cargada como SALDO_INICIAL
-- en el almacén principal.
CREATE TEMP TABLE saldo_legacy AS
SELECT a.idarticulo AS articulo_id,
       coalesce(dev.c, 0) + coalesce(comp.c, 0) + coalesce(aje.c, 0) + coalesce(tre.c, 0)
     - coalesce(fac.c, 0) - coalesce(pro.c, 0) - coalesce(ajs.c, 0) - coalesce(trs.c, 0) AS cantidad
FROM legacy.articulo a
LEFT JOIN (SELECT d.idarticulo, sum(d.cantidad) c FROM legacy.factura f JOIN legacy.detalle_factura d ON f.nofactura = d.numerofactura
           WHERE f.estado <> 'N' AND d.estado = 'A' GROUP BY 1) fac ON fac.idarticulo = a.idarticulo
LEFT JOIN (SELECT d.idarticulo, sum(d.cantidad) c FROM legacy.facturapro f JOIN legacy.detalle_facturapro d ON f.nofactura = d.numerofactura
           WHERE d.estado = 'A' AND f.estado NOT IN ('F','N') GROUP BY 1) pro ON pro.idarticulo = a.idarticulo
LEFT JOIN (SELECT d.idarticulo, sum(d.cantidad) c FROM legacy.transentsalida t JOIN legacy.detalle_transentsalidad d ON t.notrans = d.notrans
           WHERE t.estado = 'A' AND d.tipotrans = 'E' GROUP BY 1) tre ON tre.idarticulo = a.idarticulo
LEFT JOIN (SELECT d.idarticulo, sum(d.cantidad) c FROM legacy.transentsalida t JOIN legacy.detalle_transentsalidad d ON t.notrans = d.notrans
           WHERE t.estado = 'A' AND d.tipotrans = 'S' GROUP BY 1) trs ON trs.idarticulo = a.idarticulo
LEFT JOIN (SELECT d.idarticulo, sum(d.cantidad) c FROM legacy.devolucion v JOIN legacy.detalle_devolucion d ON v.nodevolucion = d.nodevolucion
           WHERE v.estado = 'A' GROUP BY 1) dev ON dev.idarticulo = a.idarticulo
LEFT JOIN (SELECT d.idarticulo, sum(d.diferencia) c FROM legacy.ajuste j JOIN legacy.detalle_ajuste d ON j.noajuste = d.noajuste
           WHERE j.estado = 'A' AND d.tipo = 'E' AND d.estado = 'A' GROUP BY 1) aje ON aje.idarticulo = a.idarticulo
LEFT JOIN (SELECT d.idarticulo, sum(d.diferencia) c FROM legacy.ajuste j JOIN legacy.detalle_ajuste d ON j.noajuste = d.noajuste
           WHERE j.estado = 'A' AND d.tipo = 'S' AND d.estado = 'A' GROUP BY 1) ajs ON ajs.idarticulo = a.idarticulo
LEFT JOIN (SELECT d.articulo AS idarticulo, sum(d.qty) c FROM legacy.compra c JOIN legacy.deta_compra d ON c.idcompra = d.idcompra
           WHERE c.estado = 'A' GROUP BY 1) comp ON comp.idarticulo = a.idarticulo
WHERE a.tipo = 'I';

INSERT INTO existencia (articulo_id, almacen_id, cantidad)
SELECT s.articulo_id, (SELECT id FROM almacen WHERE compania_id = :cia AND principal), s.cantidad
FROM saldo_legacy s JOIN articulo a ON a.id = s.articulo_id
WHERE s.cantidad <> 0;

INSERT INTO inventario_movimiento (compania_id, articulo_id, almacen_id, tipo, cantidad, costo_unitario,
                                   existencia_final, documento_tipo, documento_id, usuario)
SELECT :cia, e.articulo_id, e.almacen_id, 'SALDO_INICIAL', e.cantidad, a.costo_promedio, e.cantidad, 'MIGRACION', 0, 'migracion'
FROM existencia e JOIN articulo a ON a.id = e.articulo_id;

-- ---------------------------------------------------------------- Facturas históricas
-- Se migran para Cuentas por Cobrar e historial. Tienen NCF legacy (no e-CF).
INSERT INTO factura (id, compania_id, numero, fecha, condicion, dias_credito, fecha_vencimiento, cliente_id,
                     cliente_nombre, cliente_rnc, vendedor_id, almacen_id, tipo_precio, comentario,
                     monto_gravado, total_itbis, total, balance, ncf_legacy, estado, usuario_id, actualizado_por)
SELECT f.nofactura, :cia, f.nofactura, coalesce(f.fecha, f.actualizacion::date, date '2000-01-01'),
       CASE WHEN upper(f.tipofactura) IN ('C', 'E') THEN 'CREDITO' ELSE 'CONTADO' END,   -- A = contado; C (o E en otras versiones) = crédito
       greatest(coalesce(f.condiciondias, 0), 0), f.fechavencimiento, f.idcliente,
       coalesce(nullif(trim(f.razonsocial), ''), nullif(trim(f.nombrecli), ''), c.nombre), c.rnc_cedula,
       v.id, (SELECT id FROM almacen WHERE compania_id = :cia AND principal), c.tipo_precio, left(f.comentario, 600),
       0, 0, greatest(coalesce(f.monto, 0), 0),
       least(greatest(coalesce(f.balance, 0), 0), greatest(coalesce(f.monto, 0), 0)),
       nullif(trim(f.ncf), ''),
       CASE WHEN f.estado = 'N' THEN 'ANULADA' WHEN coalesce(f.balance, 0) <= 0 THEN 'PAGADA' ELSE 'EMITIDA' END,
       u.id, 'migracion'
FROM legacy.factura f
JOIN cliente c ON c.id = f.idcliente
LEFT JOIN vendedor v ON v.id = f.idvendedor
LEFT JOIN usuario u ON u.id = f.iduser;

INSERT INTO factura_linea (factura_id, linea, articulo_id, descripcion, unidad, cantidad, precio_unitario, descuento,
                           indicador_itbis, tasa_itbis, importe, itbis, costo_unitario)
SELECT d.numerofactura, row_number() OVER (PARTITION BY d.numerofactura ORDER BY d.iddetallefactura),
       d.idarticulo, a.nombre, left(d.unidad, 20), d.cantidad, coalesce(d.precio, 0), coalesce(d.descuento, 0),
       CASE coalesce(d.porcientoitbis, 18) WHEN 18 THEN 1 WHEN 16 THEN 2 ELSE 4 END,
       coalesce(d.porcientoitbis, 0),
       round(d.cantidad * coalesce(d.precio, 0) - coalesce(d.descuento, 0), 2), coalesce(d.itbis_pagado, 0),
       coalesce(d.costodeventa, 0)
FROM legacy.detalle_factura d
JOIN factura f ON f.id = d.numerofactura
JOIN articulo a ON a.id = d.idarticulo
WHERE d.estado = 'A' AND d.cantidad > 0;

UPDATE factura f SET total_itbis = s.itbis, monto_gravado = greatest(f.total - s.itbis, 0)
FROM (SELECT factura_id, sum(itbis) itbis FROM factura_linea GROUP BY 1) s
WHERE s.factura_id = f.id AND s.itbis <= f.total;

-- El sistema anterior no guardaba días de crédito en el cliente (se elegían al facturar): se toman los que más
-- usó cada cliente en sus facturas a crédito. Así el punto de venta los propone; se pueden cambiar en cada venta.
UPDATE cliente c SET dias_credito = s.dias
FROM (SELECT cliente_id, mode() WITHIN GROUP (ORDER BY dias_credito) dias
        FROM factura WHERE condicion = 'CREDITO' AND dias_credito > 0 GROUP BY cliente_id) s
WHERE s.cliente_id = c.id AND c.dias_credito = 0;

-- ---------------------------------------------------------------- Contadores y secuencias
INSERT INTO contador_documento (compania_id, clave, ultimo)
SELECT :cia, 'FACTURA', coalesce(max(numero), 0) FROM factura WHERE compania_id = :cia
ON CONFLICT (compania_id, clave) DO UPDATE SET ultimo = excluded.ultimo;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['zona','ciudad','unidad','turno','departamento','almacen','vendedor','cliente',
                           'proveedor','articulo','factura','factura_linea','inventario_movimiento'] LOOP
    EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), coalesce((SELECT max(id) FROM %I), 1))', t, t);
  END LOOP;
END $$;

-- Reporte de control: compárelo con el sistema legacy antes de dar por buena la migración
SELECT 'clientes' AS entidad, (SELECT count(*) FROM legacy.clientex) AS legacy, (SELECT count(*) FROM cliente) AS nuevo
UNION ALL SELECT 'articulos', (SELECT count(*) FROM legacy.articulo), (SELECT count(*) FROM articulo)
UNION ALL SELECT 'facturas', (SELECT count(*) FROM legacy.factura), (SELECT count(*) FROM factura)
UNION ALL SELECT 'CxC pendiente', (SELECT round(sum(greatest(balance, 0))::numeric, 2) FROM legacy.factura WHERE estado <> 'N' AND upper(tipofactura) IN ('C', 'E')),  -- balances negativos (pagos de más) cuentan 0
                                  (SELECT sum(balance) FROM factura WHERE condicion = 'CREDITO' AND estado <> 'ANULADA');

COMMIT;
