import { DOCUMENT } from '@angular/common';
import { Injectable, computed, effect, inject, signal } from '@angular/core';

export type ModoTema = 'claro' | 'oscuro' | 'sistema';
const CLAVE = 'agro.tema';

/**
 * Modo claro / oscuro / según el sistema. El tema de Material usa light-dark(), así que basta con fijar
 * `color-scheme` en <html>. La preferencia se guarda en este navegador (index.html la aplica antes de pintar).
 */
@Injectable({ providedIn: 'root' })
export class TemaService {
  private readonly doc = inject(DOCUMENT);
  private readonly consulta = this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)');
  private readonly sistemaOscuro = signal(!!this.consulta?.matches);

  readonly modo = signal<ModoTema>(leer());
  /** Lo que se ve en pantalla. */
  readonly oscuro = computed(() => this.modo() === 'oscuro' || (this.modo() === 'sistema' && this.sistemaOscuro()));

  constructor() {
    this.consulta?.addEventListener?.('change', (e) => this.sistemaOscuro.set(e.matches));
    effect(() => {
      const m = this.modo();
      this.doc.documentElement.style.colorScheme = m === 'claro' ? 'light' : m === 'oscuro' ? 'dark' : 'light dark';
      try { localStorage.setItem(CLAVE, m); } catch { /* almacenamiento no disponible: solo dura esta sesión */ }
    });
  }

  fijar(m: ModoTema): void { this.modo.set(m); }

  /** Botón de la barra: pasa al modo contrario al que se ve. */
  alternar(): void { this.modo.set(this.oscuro() ? 'claro' : 'oscuro'); }
}

function leer(): ModoTema {
  try {
    const v = localStorage.getItem(CLAVE);
    return v === 'claro' || v === 'oscuro' || v === 'sistema' ? v : 'sistema';
  } catch {
    return 'sistema';
  }
}
