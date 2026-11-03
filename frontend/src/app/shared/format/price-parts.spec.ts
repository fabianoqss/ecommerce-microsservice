import { priceParts } from './price-parts';

describe('priceParts', () => {
  it.each([
    [89.9, '89', ',90'],
    [1999, '1.999', ',00'],
    [2779, '2.779', ',00'],
    [0, '0', ',00'],
    [1234567.89, '1.234.567', ',89'],
  ])('formata %s em partes brasileiras', (price, integer, decimal) => {
    expect(priceParts(price)).toEqual({ integer, decimal });
  });
});
