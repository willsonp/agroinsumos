-- =====================================================================
-- Migración: notas de crédito / débito de suplidores (legacy notacreditocxp / notadebitocxp)
-- En el sistema anterior estas notas no indicaban la compra que modificaban, por lo que no pueden
-- migrarse automáticamente a nota_proveedor (que exige la compra afectada y el NCF modificado para el 606).
-- Este script solo lista las que tienen balance pendiente para registrarlas en "Notas de suplidores".
-- Ejecutar:  psql -v cia=1 -f 06_notas_proveedor.sql
-- =====================================================================
\if :{?cia} \else \set cia 1 \endif

-- 1) Notas de crédito con saldo a favor de la empresa (aplicar contra una compra pendiente del suplidor)
SELECT 'CREDITO' tipo, n.nocredito numero, n.fecha, p.id proveedor_id, p.nombre, round(n.monto::numeric, 2) monto,
       round(n.balance::numeric, 2) balance, n.concepto
FROM legacy.notacreditocxp n JOIN proveedor p ON p.id = n.idproveedor
WHERE upper(coalesce(n.estado, 'A')) = 'A' AND coalesce(n.balance, 0) > 0
UNION ALL
-- 2) Notas de débito pendientes de pago (registrar como nota de débito sobre la compra que corresponda)
SELECT 'DEBITO', n.nodebito, n.fecha, p.id, p.nombre, round(n.monto::numeric, 2), round(n.balance::numeric, 2), NULL
FROM legacy.notadebitocxp n JOIN proveedor p ON p.id = n.idproveedor
WHERE upper(coalesce(n.estado, 'A')) = 'A' AND coalesce(n.balance, 0) > 0 AND coalesce(n.idcompania, :cia) = :cia
ORDER BY 1, 3;

-- 3) Compras pendientes de esos suplidores (candidatas para aplicar las notas)
SELECT c.proveedor_id, c.id compra_id, c.numero, c.ncf_proveedor, c.fecha, c.balance
FROM compra c
WHERE c.compania_id = :cia AND c.balance > 0 AND c.estado <> 'ANULADA'
  AND c.proveedor_id IN (SELECT idproveedor FROM legacy.notacreditocxp WHERE coalesce(balance, 0) > 0
                         UNION SELECT idproveedor FROM legacy.notadebitocxp WHERE coalesce(balance, 0) > 0)
ORDER BY c.proveedor_id, c.fecha;
