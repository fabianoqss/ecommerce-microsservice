import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AdminMenu } from '../admin-menu/admin-menu';

@Component({
  selector: 'app-admin-shell',
  imports: [AdminMenu, RouterOutlet],
  templateUrl: './admin-shell.html',
})
export class AdminShell {}
