import { parsePrice } from './parse-price';

describe('parsePrice', () => {
  it.each([
    ['1234,56', 1234.56],
    ['1.234,56', 1234.56],
    ['1234', 1234],
    ['1.234', 1234],
    ['12,3', 12.3],
    ['0,01', 0.01],
    ['999.999.999.999,99', 999999999999.99],
    [' 12,50 ', 12.5],
  ])('converte %s para %s', (input, expected) => {
    expect(parsePrice(input)).toBe(expected);
  });

  it.each([
    '',
    ' ',
    '0',
    '0,00',
    '-1',
    '+1',
    '1234.56',
    '1,234',
    '1.23,45',
    '12.34',
    '1.2345',
    '1.234.56',
    '1 234,56',
    '1e3',
    'NaN',
    'Infinity',
    '1000000000000',
    '999999999999,999',
    ',50',
    '1,',
    '1.234,567',
  ])('rejeita %s', (input) => {
    expect(parsePrice(input)).toBeNull();
  });
});
