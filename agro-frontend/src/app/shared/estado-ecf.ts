import { Component, computed, input } from '@angular/core';

const ESTILOS: Record<string, [string, string]> = {
  FIRMADO: ['info', 'Firmado (pendiente de envío)'],
  ENVIADO: ['info', 'Enviado a la DGII'],
  EN_PROCESO: ['warn', 'En proceso'],
  ACEPTADO: ['ok', 'Aceptado'],
  ACEPTADO_CONDICIONAL: ['warn', 'Aceptado condicional'],
  RECHAZADO: ['error', 'Rechazado'],
  ERROR_ENVIO: ['error', 'Error de envío (se reintentará)'],
};

@Component({
  selector: 'app-estado-ecf',
  template: `<span class="chip" [class]="estilo()[0]">{{ estilo()[1] }}</span>`,
  host: { style: 'display:contents' },
})
export class EstadoEcf {
  readonly estado = input<string | undefined>();
  protected readonly estilo = computed(() => ESTILOS[this.estado() ?? ''] ?? ['', this.estado() ?? '—']);
}
