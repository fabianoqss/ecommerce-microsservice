import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ButtonModule } from 'primeng/button';

@Component({
  imports: [RouterOutlet, ButtonModule],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App {}
