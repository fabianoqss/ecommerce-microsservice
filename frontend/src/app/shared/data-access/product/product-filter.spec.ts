import { parseProductFilter } from './product-filter';

describe('parseProductFilter', () => {
  it('usa a primeira página e filtros vazios quando os parâmetros estão ausentes', () => {
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
  ])('normaliza a página inválida %s para a primeira', (page) => {
    expect(parseProductFilter({ page })).toEqual({ page: 0, name: '', category: '' });
  });

  it.each([
    ['1', 0],
    ['2', 1],
    ['0002', 1],
    ['2147483647', 2147483646],
  ])('converte a página %s para o índice %s da API', (page, expected) => {
    expect(parseProductFilter({ page: String(page) }).page).toBe(expected);
  });

  it('ignora parâmetros repetidos', () => {
    expect(
      parseProductFilter({ page: ['1', '2'], name: ['A', 'B'], category: ['A', 'B'] }),
    ).toEqual({ page: 0, name: '', category: '' });
  });

  it('ignora arrays mesmo quando contêm um único valor', () => {
    expect(
      parseProductFilter({ page: ['2'], name: ['Teclado'], category: ['Periféricos'] }),
    ).toEqual({
      page: 0,
      name: '',
      category: '',
    });
  });

  it('apara nome e categoria preservando espaços internos', () => {
    expect(
      parseProductFilter({ name: '  Teclado mecânico  ', category: '  Áudio e vídeo  ' }),
    ).toEqual({
      page: 0,
      name: 'Teclado mecânico',
      category: 'Áudio e vídeo',
    });
  });

  it('limita o nome a cem caracteres depois de aparar', () => {
    expect(parseProductFilter({ name: '  ' + 'a'.repeat(101) + '  ' }).name).toBe('a'.repeat(100));
  });

  it('mantém categorias longas sem truncar', () => {
    expect(parseProductFilter({ category: '  ' + 'a'.repeat(101) + '  ' }).category).toBe(
      'a'.repeat(101),
    );
  });

  it('normaliza filtros com apenas espaços para vazio', () => {
    expect(parseProductFilter({ name: ' \t ', category: ' \n ' })).toEqual({
      page: 0,
      name: '',
      category: '',
    });
  });
});
