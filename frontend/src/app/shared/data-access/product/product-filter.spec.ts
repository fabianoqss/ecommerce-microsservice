import { parseProductFilter } from './product-filter';

describe('parseProductFilter', () => {
  it('uses the first page and empty filters when parameters are absent', () => {
    expect(parseProductFilter({})).toEqual({ page: 0, name: '', category: '' });
  });

  it.each([
    '',
    '0',
    '0000',
    '-1',
    'abc',
    '1.5',
    '1e2',
    '2147483648',
    '999999999999999999999',
    ' 2 ',
    '+2',
  ])('normalizes invalid page %s to the first page', (page) => {
    expect(parseProductFilter({ page })).toEqual({ page: 0, name: '', category: '' });
  });

  it.each([
    ['1', 0],
    ['2', 1],
    ['0002', 1],
    ['2147483647', 2147483646],
  ])('converts page %s to API index %s', (page, expected) => {
    expect(parseProductFilter({ page: String(page) }).page).toBe(expected);
  });

  it('ignores repeated parameters', () => {
    expect(
      parseProductFilter({ page: ['1', '2'], name: ['A', 'B'], category: ['A', 'B'] }),
    ).toEqual({ page: 0, name: '', category: '' });
  });

  it('ignores arrays even when they contain a single value', () => {
    expect(
      parseProductFilter({ page: ['2'], name: ['Keyboard'], category: ['Peripherals'] }),
    ).toEqual({
      page: 0,
      name: '',
      category: '',
    });
  });

  it('trims name and category while preserving internal spaces', () => {
    expect(
      parseProductFilter({ name: '  Mechanical keyboard  ', category: '  Audio and video  ' }),
    ).toEqual({
      page: 0,
      name: 'Mechanical keyboard',
      category: 'Audio and video',
    });
  });

  it('limits the name to one hundred characters after trimming', () => {
    expect(parseProductFilter({ name: '  ' + 'a'.repeat(101) + '  ' }).name).toBe('a'.repeat(100));
  });

  it('preserves long categories without truncation', () => {
    expect(parseProductFilter({ category: '  ' + 'a'.repeat(101) + '  ' }).category).toBe(
      'a'.repeat(101),
    );
  });

  it('normalizes whitespace-only filters to empty', () => {
    expect(parseProductFilter({ name: ' \t ', category: ' \n ' })).toEqual({
      page: 0,
      name: '',
      category: '',
    });
  });
});
