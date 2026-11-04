import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { Toast } from 'primeng/toast';
import { AdminMenu } from '../admin-menu/admin-menu';

@Component({
  selector: 'app-admin-shell',
  imports: [AdminMenu, RouterOutlet, Toast],
  providers: [MessageService, ConfirmationService],
  templateUrl: './admin-shell.html',
})
export class AdminShell {}
