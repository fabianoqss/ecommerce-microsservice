import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CardModule } from 'primeng/card';

@Component({
  imports: [CardModule, RouterLink],
  selector: 'app-home',
  templateUrl: './home.html',
})
export class Home {}
