import { Component, ElementRef, effect, input, viewChild } from '@angular/core';
import QRCode from 'qrcode';

/** Código QR (timbre fiscal del e-CF: URL de consulta en la DGII). */
@Component({
  selector: 'app-qr',
  template: '<canvas #lienzo></canvas>',
})
export class Qr {
  readonly valor = input.required<string>();
  readonly tamano = input(140);
  private readonly lienzo = viewChild.required<ElementRef<HTMLCanvasElement>>('lienzo');

  constructor() {
    effect(() => {
      QRCode.toCanvas(this.lienzo().nativeElement, this.valor(), { width: this.tamano(), margin: 1 });
    });
  }
}
