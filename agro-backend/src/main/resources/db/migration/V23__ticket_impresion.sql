-- Ticket POS: lema bajo el encabezado y mensaje al pie; contador de impresiones (Original / Reimpresión)
ALTER TABLE compania ADD COLUMN lema VARCHAR(100);
ALTER TABLE compania ADD COLUMN pie_ticket VARCHAR(150);
ALTER TABLE factura ADD COLUMN impresiones INTEGER NOT NULL DEFAULT 0;
