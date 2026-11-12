import { TestBed } from '@angular/core/testing';
import { CheckoutAttempt } from './checkout-attempt';

describe('CheckoutAttempt', () => {
  let service: CheckoutAttempt;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(CheckoutAttempt);
  });

  it('keeps the same UUID for the same snapshot', () => {
    const key = service.keyFor('cart-a');
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(service.keyFor('cart-a')).toBe(key);
  });

  it('generates another UUID when the snapshot changes, including a return to the previous one', () => {
    const first = service.keyFor('cart-a');
    const second = service.keyFor('cart-b');
    expect(second).not.toBe(first);
    const third = service.keyFor('cart-a');
    expect(third).not.toBe(first);
    expect(third).not.toBe(second);
  });

  it('renews the key after rejection even with the same snapshot', () => {
    const first = service.keyFor('cart-a');
    service.renew();
    const renewed = service.keyFor('cart-a');
    expect(renewed).not.toBe(first);
    expect(service.keyFor('cart-a')).toBe(renewed);
  });

  it('forgets the completed attempt to allow another order from the same snapshot', () => {
    const first = service.keyFor('cart-a');
    service.complete();
    const next = service.keyFor('cart-a');
    expect(next).not.toBe(first);
    expect(service.keyFor('cart-a')).toBe(next);
  });
});
