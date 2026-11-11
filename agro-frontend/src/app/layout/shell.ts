import { Component, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, startWith } from 'rxjs';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../core/auth.service';
import { Modulo, NAVEGACION, Opcion } from '../core/navegacion';
import { colorModulo } from '../core/colores-modulo';

const NIVELES: Record<string, string> = { ADMIN: 'Administrador', SUPERVISOR: 'Supervisor', USUARIO: 'Usuario', OTRO: 'Consulta' };

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatToolbarModule,
    MatButtonModule, MatMenuModule, MatDividerModule, MatTooltipModule],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly color = colorModulo;
  protected readonly movil = toSignal(
    inject(BreakpointObserver).observe('(max-width: 900px)').pipe(map((r) => r.matches)), { initialValue: false });
  /** Abierto por defecto en escritorio y cerrado en móvil; se reinicia al cambiar de tamaño. */
  protected readonly menuAbierto = linkedSignal(() => !this.movil());
  protected readonly busqueda = signal('');
  /** Módulos desplegados (por nombre). */
  protected readonly abiertos = signal<Set<string>>(new Set());

  private readonly url = toSignal(this.router.events.pipe(
    filter((e) => e instanceof NavigationEnd), map(() => this.router.url), startWith(this.router.url)), { initialValue: '' });

  /** Solo módulos/opciones que el usuario tiene permitidos. */
  protected readonly modulos = computed(() => {
    this.auth.perfil(); // dependencia: recalcula al cambiar el perfil
    return NAVEGACION.map((m) => ({ ...m, opciones: m.opciones.filter((o) => this.auth.puede(o.codigo)) }))
      .filter((m) => m.opciones.length > 0);
  });

  /** Resultado del buscador: opciones cuyo nombre o módulo contiene el texto (sin tildes). */
  protected readonly resultados = computed(() => {
    const q = normalizar(this.busqueda().trim());
    if (!q) return [];
    return this.modulos().flatMap((m) => m.opciones.map((o) => ({ modulo: m, opcion: o })))
      .filter((x) => normalizar(x.opcion.nombre).includes(q) || normalizar(x.modulo.nombre).includes(q));
  });

  /** Módulo y opción de la ruta actual (para la miga de pan y para desplegar su grupo). */
  protected readonly actual = computed<{ modulo: Modulo; opcion: Opcion } | null>(() => {
    const ruta = this.url().split('?')[0];
    let mejor: { modulo: Modulo; opcion: Opcion } | null = null;
    for (const m of this.modulos()) for (const o of m.opciones) {
      if ((ruta === o.ruta || ruta.startsWith(o.ruta + '/')) && (!mejor || o.ruta.length > mejor.opcion.ruta.length)) mejor = { modulo: m, opcion: o };
    }
    return mejor;
  });

  protected readonly iniciales = computed(() => {
    const n = (this.auth.perfil()?.nombre ?? this.auth.perfil()?.username ?? '?').trim().split(/\s+/);
    return ((n[0]?.[0] ?? '') + (n[1]?.[0] ?? '')).toUpperCase();
  });
  protected readonly nivel = computed(() => NIVELES[this.auth.perfil()?.nivel ?? ''] ?? '');

  constructor() {
    // Al navegar se despliega el módulo de la opción actual (sin cerrar los que el usuario abrió)
    effect(() => {
      const a = this.actual();
      if (a && !this.abiertos().has(a.modulo.nombre)) this.abiertos.update((s) => new Set(s).add(a.modulo.nombre));
    });
  }

  protected alternar(nombre: string): void {
    this.abiertos.update((s) => { const n = new Set(s); if (n.has(nombre)) n.delete(nombre); else n.add(nombre); return n; });
  }

  protected alNavegar(): void {
    this.busqueda.set('');
    if (this.movil()) this.menuAbierto.set(false);
  }

  protected irAlPrimero(): void {
    const r = this.resultados()[0];
    if (r) { this.router.navigateByUrl(r.opcion.ruta); this.alNavegar(); }
  }
}

function normalizar(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}
