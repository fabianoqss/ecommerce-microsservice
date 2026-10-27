import { Component } from '@angular/core';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';

@Component({
  imports: [CardModule, InputTextModule],
  selector: 'app-login',
  templateUrl: './login.html',
})
export class Login {}
