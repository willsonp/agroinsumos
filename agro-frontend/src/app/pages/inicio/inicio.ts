import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../../core/auth.service';
import { NAVEGACION } from '../../core/navegacion';
import type { S } from '../../api/tipos';

@Component({
  selector: 'app-inicio',
  imports: [RouterLink, MatButtonModule, CurrencyPipe, DatePipe],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Hola, {{ auth.perfil()?.nombre }}</h1></div>
      <div class="tarjetas">
        @if (auth.puede('CAJA_SESION')) {
          <div class="tarjeta">
            <h3><span class="material-symbols-outlined">account_balance_wallet</span> Mi caja</h3>
            @if (caja(); as c) {
              <p>Caja <strong>#{{ c.numero }}</strong> abierta desde {{ c.abiertaEn | date: 'short' }}</p>
              <p>Efectivo esperado: <strong>{{ c.cuadre?.efectivoEsperado | currency }}</strong></p>
            } @else if (cajaCargada()) {
              <p class="muted">No tiene una caja abierta.</p>
            }
            <a mat-stroked-button routerLink="/caja">Ir a mi caja</a>
          </div>
        }
        @for (o of accesos(); track o.codigo) {
          <a class="tarjeta acceso" [routerLink]="o.ruta">
            <span class="material-symbols-outlined">{{ o.icono }}</span>
            <span>{{ o.nombre }}</span>
          </a>
        }
      </div>
    </div>`,
  styles: `
    .tarjetas { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
    h3 { display: flex; gap: 8px; align-items: center; margin: 0 0 8px; }
    .acceso { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; text-decoration: none;
      color: inherit; justify-content: center; min-height: 110px;
      .material-symbols-outlined { font-size: 32px; color: var(--mat-sys-primary); }
      &:hover { background: var(--mat-sys-surface-container); } }`,
})
export class Inicio {
  protected readonly auth = inject(AuthService);
  protected readonly caja = signal<S['CajaSesionResponse'] | null>(null);
  protected readonly cajaCargada = signal(false);
  protected readonly accesos = computed(() => NAVEGACION.flatMap((m) => m.opciones)
    .filter((o) => this.auth.puede(o.codigo) && ['VENTAS_POS', 'VENTAS_FACTURAS', 'CXC_RECIBOS', 'INV_ARTICULOS', 'COMPRAS_REGISTRO', 'FISCAL_FORMATOS'].includes(o.codigo)));

  constructor() {
    if (this.auth.puede('CAJA_SESION')) {
      inject(HttpClient).get<S['CajaSesionResponse'] | null>('/api/v1/caja/sesiones/actual').subscribe({
        next: (c) => { this.caja.set(c); this.cajaCargada.set(true); },
        error: () => this.cajaCargada.set(true),
      });
    }
  }
}
