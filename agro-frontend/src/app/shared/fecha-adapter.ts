import { Injectable } from '@angular/core';
import { NativeDateAdapter } from '@angular/material/core';

/**
 * Adaptador de fechas de los calendarios: muestra dd/mm/aaaa (es-DO) y, al escribir a mano, interpreta día/mes/año
 * (el adaptador nativo usa Date.parse, que lee "13/11/2026" al estilo de EE. UU. y lo rechaza o lo confunde).
 */
@Injectable()
export class FechaAdapter extends NativeDateAdapter {
  override parse(value: unknown, formato?: object): Date | null {
    if (typeof value === 'string') {
      const t = value.trim();
      if (!t) return null;
      const m = /^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2}|\d{4})$/.exec(t);
      if (!m) return this.invalid();
      const dia = +m[1], mes = +m[2], anio = m[3].length === 2 ? 2000 + +m[3] : +m[3];
      const d = new Date(anio, mes - 1, dia);
      // Rechaza 31/02/2026 (Date lo "corrige" a marzo)
      return d.getFullYear() === anio && d.getMonth() === mes - 1 && d.getDate() === dia ? d : this.invalid();
    }
    return super.parse(value, formato);
  }
}
