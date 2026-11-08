import { TestBed } from '@angular/core/testing';
import { CheckoutAttempt } from './checkout-attempt';

describe('CheckoutAttempt', () => {
  let service: CheckoutAttempt;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(CheckoutAttempt);
  });

  it('mantém o mesmo UUID para o mesmo snapshot', () => {
    const key = service.keyFor('carrinho-a');
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(service.keyFor('carrinho-a')).toBe(key);
  });

  it('gera outro UUID quando o snapshot muda, inclusive ao voltar ao anterior', () => {
    const first = service.keyFor('carrinho-a');
    const second = service.keyFor('carrinho-b');
    expect(second).not.toBe(first);
    const third = service.keyFor('carrinho-a');
    expect(third).not.toBe(first);
    expect(third).not.toBe(second);
  });

  it('renova a chave após rejeição mesmo mantendo o snapshot', () => {
    const first = service.keyFor('carrinho-a');
    service.renew();
    const renewed = service.keyFor('carrinho-a');
    expect(renewed).not.toBe(first);
    expect(service.keyFor('carrinho-a')).toBe(renewed);
  });

  it('esquece a tentativa concluída para permitir outro pedido do mesmo snapshot', () => {
    const first = service.keyFor('carrinho-a');
    service.complete();
    const next = service.keyFor('carrinho-a');
    expect(next).not.toBe(first);
    expect(service.keyFor('carrinho-a')).toBe(next);
  });
});
