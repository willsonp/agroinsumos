import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSelectModule } from '@angular/material/select';
import type { S } from '../../api/tipos';
import { descargar, params } from '../../shared/http';
import { Notificacion } from '../../core/notificacion.service';

type Reporte = S['Formato606Reporte'] | S['Formato607Reporte'] | S['Formato608Reporte'];

const TIPOS_ANULACION: [string, string][] = [['01', 'Deterioro de factura preimpresa'], ['02', 'Errores de impresión (preimpresa)'],
  ['03', 'Impresión defectuosa'], ['04', 'Corrección de la información'], ['05', 'Cambio de productos'], ['06', 'Devolución de productos'],
  ['07', 'Omisión de productos'], ['08', 'Errores en secuencia de NCF'], ['09', 'Por cese de operaciones'], ['10', 'Pérdida o hurto de talonarios']];

/** Formatos de envío 606 (compras), 607 (ventas) y 608 (comprobantes anulados) de la DGII. */
import { Fecha } from '../../shared/fecha';
@Component({
  selector: 'app-formatos',
  imports: [Fecha, CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule],
  templateUrl: './formatos.html',
  styles: `.tabla { width: 100%; border-collapse: collapse; font-size: 13px; td, th { padding: 5px 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; white-space: nowrap; } }
    .kpis { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; margin: 12px 0;
      div { font: var(--mat-sys-title-large); } label { display: block; font: var(--mat-sys-label-medium); color: var(--mat-sys-on-surface-variant); } }
    .grid-anul { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 18px 12px; align-items: start;
      .motivo { grid-column: span 2; } .registrar { margin-top: 8px; justify-self: start; } }
    @media (max-width: 600px) { .grid-anul .motivo { grid-column: auto; } }
    .advertencias { background: var(--aviso-fondo); color: var(--aviso-texto); border-radius: 8px; padding: 8px 16px; li { margin: 4px 0; } }`,
})
export class Formatos {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected formato: '606' | '607' | '608' = '607';
  protected readonly meses = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre',
    'Noviembre', 'Diciembre'];
  /** Año actual y los 5 anteriores (la DGII permite remitir o rectificar periodos pasados). */
  protected readonly anios = Array.from({ length: 6 }, (_, i) => new Date().getFullYear() - i);
  // Por defecto, el mes anterior: es el que normalmente se declara (vence el 15 del mes siguiente)
  protected mes = this.mesAnterior().getMonth() + 1;
  protected anio = this.mesAnterior().getFullYear();
  protected incluirEcf = true;
  protected readonly reporte = signal<Reporte | null>(null);
  protected readonly r606 = () => this.reporte() as S['Formato606Reporte'];
  protected readonly r607 = () => this.reporte() as S['Formato607Reporte'];
  protected readonly r608 = () => this.reporte() as S['Formato608Reporte'];
  protected readonly tiposAnulacion = TIPOS_ANULACION;
  protected anul = this.anulacionVacia();

  constructor() { this.generar(); }

  private mesAnterior(): Date {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() - 1);
    return d;
  }

  /** Periodo AAAAMM que piden los formatos de la DGII. */
  private aaaamm(): string { return `${this.anio}${String(this.mes).padStart(2, '0')}`; }

  protected generar(): void {
    this.reporte.set(null);
    this.http.get<Reporte>(`/api/v1/fiscal/${this.formato}`, { params: params({ periodo: this.aaaamm(), incluirEcf: this.formato === '607' ? this.incluirEcf : null }) })
      .subscribe({ next: (r) => this.reporte.set(r), error: (e) => this.notif.error(e) });
  }

  protected registrarAnulacion(): void {
    const a = this.anul;
    this.http.post('/api/v1/fiscal/anulaciones', { ncf: a.ncf.trim().toUpperCase(), fechaComprobante: a.fechaComprobante,
      fechaAnulacion: a.fechaAnulacion || undefined, tipoAnulacion: a.tipoAnulacion, motivo: a.motivo || undefined }).subscribe({
      next: () => { this.notif.ok(`Comprobante ${a.ncf.toUpperCase()} registrado como anulado`); this.anul = this.anulacionVacia(); this.generar(); },
      error: (e) => this.notif.error(e) });
  }

  protected eliminarAnulacion(id: number): void {
    this.http.delete(`/api/v1/fiscal/anulaciones/${id}`).subscribe({
      next: () => { this.notif.ok('Anulación quitada'); this.generar(); }, error: (e) => this.notif.error(e) });
  }

  private anulacionVacia() { return { ncf: '', fechaComprobante: '', fechaAnulacion: '', tipoAnulacion: '04', motivo: '' }; }

  protected txt(): void {
    const rep = this.reporte()!;
    this.http.get(`/api/v1/fiscal/${this.formato}/txt`, { responseType: 'blob',
      params: params({ periodo: this.aaaamm(), incluirEcf: this.formato === '607' ? this.incluirEcf : null }) })
      .subscribe((b) => descargar(b, `DGII_F_${this.formato}_${rep.rnc}_${this.aaaamm()}.TXT`));
  }
}
