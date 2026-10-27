import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Header } from './core/layout/header/header';

@Component({
  imports: [RouterOutlet, Header],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App {}
