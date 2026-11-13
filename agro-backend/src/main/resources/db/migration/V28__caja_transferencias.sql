-- Las transferencias cobradas ahora se anotan en la caja del cajero (cuentan en el cuadre como "depósitos", igual que
-- en el sistema anterior; no cambian el efectivo esperado). Se completan los recibos ya cobrados en una caja cuya
-- transferencia no quedó anotada (solo recibos vigentes; los anulados se compensaban a cero).
INSERT INTO caja_movimiento (sesion_id, fecha, tipo, forma_pago, monto, referencia_tipo, referencia_id, descripcion)
SELECT r.sesion_caja_id,
       coalesce((SELECT min(m.fecha) FROM caja_movimiento m WHERE m.referencia_tipo = 'RECIBO' AND m.referencia_id = r.id), now()),
       'COBRO', 'TRANSFERENCIA', p.monto, 'RECIBO', r.id, 'Recibo ' || r.numero
  FROM recibo r
  JOIN recibo_pago p ON p.recibo_id = r.id AND p.forma_pago = 'TRANSFERENCIA' AND p.monto > 0
 WHERE r.sesion_caja_id IS NOT NULL
   AND r.estado = 'EMITIDO'
   AND NOT EXISTS (SELECT 1 FROM caja_movimiento m
                    WHERE m.referencia_tipo = 'RECIBO' AND m.referencia_id = r.id AND m.forma_pago = 'TRANSFERENCIA');
