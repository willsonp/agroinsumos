import { Component, computed, inject, linkedSignal } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatListModule } from '@angular/material/list';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../core/auth.service';
import { NAVEGACION } from '../core/navegacion';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatToolbarModule, MatListModule,
    MatButtonModule, MatMenuModule, MatDividerModule],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly movil = toSignal(
    inject(BreakpointObserver).observe('(max-width: 900px)').pipe(map((r) => r.matches)), { initialValue: false });
  /** Abierto por defecto en escritorio y cerrado en móvil; se reinicia al cambiar de tamaño. */
  protected readonly menuAbierto = linkedSignal(() => !this.movil());

  /** Solo módulos/opciones que el usuario tiene permitidos. */
  protected readonly modulos = computed(() => {
    this.auth.perfil(); // dependencia: recalcula al cambiar el perfil
    return NAVEGACION.map((m) => ({ ...m, opciones: m.opciones.filter((o) => this.auth.puede(o.codigo)) }))
      .filter((m) => m.opciones.length > 0);
  });

  protected alNavegar(): void {
    if (this.movil()) this.menuAbierto.set(false);
  }
}
