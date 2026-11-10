import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import type { S } from '../../api/tipos';
import { descargar, params } from '../../shared/http';
import { Notificacion } from '../../core/notificacion.service';

type Reporte = S['Formato606Reporte'] | S['Formato607Reporte'];

/** Formatos de envío 606 (compras) y 607 (ventas) de la DGII. */
@Component({
  selector: 'app-formatos',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatInputModule, MatSlideToggleModule],
  templateUrl: './formatos.html',
  styles: `.tabla { width: 100%; border-collapse: collapse; font-size: 13px; td, th { padding: 5px 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; white-space: nowrap; } }
    .kpis { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; margin: 12px 0;
      div { font: var(--mat-sys-title-large); } label { display: block; font: var(--mat-sys-label-medium); color: var(--mat-sys-on-surface-variant); } }
    .advertencias { background: #fff1c2; border-radius: 8px; padding: 8px 16px; li { margin: 4px 0; } }`,
})
export class Formatos {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected formato: '606' | '607' = '607';
  protected periodo = this.periodoAnterior();
  protected incluirEcf = true;
  protected readonly reporte = signal<Reporte | null>(null);
  protected readonly r606 = () => this.reporte() as S['Formato606Reporte'];
  protected readonly r607 = () => this.reporte() as S['Formato607Reporte'];

  constructor() { this.generar(); }

  /** Mes anterior en formato AAAA-MM (input type="month"): es el que normalmente se declara. */
  private periodoAnterior(): string {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() - 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  }

  private aaaamm(): string { return this.periodo.replace('-', ''); }

  protected generar(): void {
    this.reporte.set(null);
    this.http.get<Reporte>(`/api/v1/fiscal/${this.formato}`, { params: params({ periodo: this.aaaamm(), incluirEcf: this.formato === '607' ? this.incluirEcf : null }) })
      .subscribe({ next: (r) => this.reporte.set(r), error: (e) => this.notif.error(e) });
  }

  protected txt(): void {
    const rep = this.reporte()!;
    this.http.get(`/api/v1/fiscal/${this.formato}/txt`, { responseType: 'blob',
      params: params({ periodo: this.aaaamm(), incluirEcf: this.formato === '607' ? this.incluirEcf : null }) })
      .subscribe((b) => descargar(b, `DGII_F_${this.formato}_${rep.rnc}_${this.aaaamm()}.TXT`));
  }
}
