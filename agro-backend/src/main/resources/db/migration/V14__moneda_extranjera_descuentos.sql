-- =====================================================================
-- V14: Moneda extranjera y descuentos en ventas en cuenta separada
-- Los montos contables y fiscales siguen en pesos (DOP); los documentos en otra moneda guardan su tasa.
-- =====================================================================

-- Tasa de cambio (pesos por unidad de la moneda) vigente desde la fecha indicada
CREATE TABLE tasa_cambio (
    compania_id  BIGINT        NOT NULL REFERENCES compania(id),
    moneda       VARCHAR(3)    NOT NULL CHECK (moneda ~ '^[A-Z]{3}$' AND moneda <> 'DOP'),
    fecha        DATE          NOT NULL,
    tasa         NUMERIC(12,4) NOT NULL CHECK (tasa > 0),
    usuario_id   BIGINT        REFERENCES usuario(id),
    creado_en    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (compania_id, moneda, fecha)
);

ALTER TABLE cuenta_bancaria ADD CONSTRAINT ck_cuenta_bancaria_moneda CHECK (moneda ~ '^[A-Z]{3}$');
-- Movimientos de cuentas en otra moneda: monto en la moneda de la cuenta y su tasa para contabilizar en pesos
ALTER TABLE movimiento_bancario ADD COLUMN tasa_cambio NUMERIC(12,4) NOT NULL DEFAULT 1 CHECK (tasa_cambio > 0);

-- Compras en otra moneda: los montos de la compra se guardan en pesos a la tasa de la compra;
-- el total y el saldo pendiente se llevan además en la moneda original
ALTER TABLE compra ADD COLUMN moneda VARCHAR(3) NOT NULL DEFAULT 'DOP' CHECK (moneda ~ '^[A-Z]{3}$'),
                   ADD COLUMN tasa_cambio NUMERIC(12,4) NOT NULL DEFAULT 1 CHECK (tasa_cambio > 0),
                   ADD COLUMN total_moneda NUMERIC(18,2),
                   ADD COLUMN balance_moneda NUMERIC(18,2);

-- Pagos: en la moneda de las compras que pagan; diferencia cambiaria = pesos pagados − pesos históricos aplicados
ALTER TABLE pago_proveedor ADD COLUMN moneda VARCHAR(3) NOT NULL DEFAULT 'DOP' CHECK (moneda ~ '^[A-Z]{3}$'),
                           ADD COLUMN tasa_cambio NUMERIC(12,4) NOT NULL DEFAULT 1 CHECK (tasa_cambio > 0),
                           ADD COLUMN diferencia_cambiaria NUMERIC(18,2) NOT NULL DEFAULT 0;
ALTER TABLE pago_proveedor_aplicacion ADD COLUMN monto_dop NUMERIC(18,2);
UPDATE pago_proveedor_aplicacion SET monto_dop = monto;
ALTER TABLE pago_proveedor_aplicacion ALTER COLUMN monto_dop SET NOT NULL;

-- Cuenta de descuentos en ventas que ya traía la compañía (legacy compania.DescuentoEnVenta)
INSERT INTO cuenta_contable_config (compania_id, clave, codigo)
SELECT id, 'DESCUENTOS_VENTAS', trim(cuenta_descuento_venta) FROM compania
WHERE nullif(trim(cuenta_descuento_venta), '') IS NOT NULL
ON CONFLICT (compania_id, clave) DO NOTHING;

INSERT INTO sub_menu (main_menu_id, nombre_opcion, codigo, ruta_frontend, orden)
SELECT m.id, 'Tasas de cambio', 'BANCO_TASAS', '/banco/tasas', 4 FROM main_menu m WHERE m.codigo = 'BANCO';
