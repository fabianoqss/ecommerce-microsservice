import { Component, inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { Toast } from 'primeng/toast';
import { RouterLink, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-public-layout',
  imports: [RouterOutlet, RouterLink, Toast],
  templateUrl: './public-layout.html',
})
export class PublicLayout {
  private readonly messages = inject(MessageService);

  protected closeCartToast(): void {
    this.messages.clear('cart');
  }
}
