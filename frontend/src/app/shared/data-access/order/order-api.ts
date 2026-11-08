import { HttpClient, httpResource } from '@angular/common/http';
import { inject, Service } from '@angular/core';
import { map, Observable } from 'rxjs';
import { Order } from './order';
import { CreateOrderRequest } from './order-request';

@Service()
export class OrderApi {
  private readonly http = inject(HttpClient);

  create(request: CreateOrderRequest, idempotencyKey: string): Observable<Order> {
    return this.http
      .post<Order>('/api/order', request, {
        headers: { 'Idempotency-Key': idempotencyKey },
        observe: 'response',
      })
      .pipe(
        map((response) => {
          if (response.status !== 201 || response.body === null) {
            throw new Error('Resposta inesperada ao criar o pedido.');
          }
          return response.body;
        }),
      );
  }

  byId(id: () => string) {
    return httpResource<Order>(() => '/api/order/' + encodeURIComponent(id()));
  }
}
