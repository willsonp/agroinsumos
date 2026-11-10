-- =====================================================================
-- V13: Cierre anual del ejercicio y sobrantes / faltantes de caja
-- =====================================================================

-- Un documento tiene un solo asiento VIGENTE (y un solo reverso). Un asiento anulado (p.ej. un cierre anual
-- que se deshace para reabrir el ejercicio) ya no impide volver a generarlo.
DROP INDEX uk_asiento_origen;
CREATE UNIQUE INDEX uk_asiento_origen ON asiento (compania_id, origen_tipo, origen_id, reverso)
    WHERE origen_tipo IS NOT NULL AND estado = 'REGISTRADO';
