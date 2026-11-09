import { HttpErrorResponse } from '@angular/common/http';
import { problemDetail, problemFields } from './problem-detail';

describe('ProblemDetail administrativo', () => {
  it('extrai detail e o mapa fields do contrato real', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { detail: 'Revise os campos.', fields: { name: 'Nome inválido.' } },
    });
    expect(problemDetail(error, 'Falha.')).toBe('Revise os campos.');
    expect(problemFields(error)).toEqual({ name: 'Nome inválido.' });
  });

  it.each([null, 'Erro', {}, { detail: ' ' }, { detail: 12 }, { fields: [] }, { fields: null }])(
    'usa fallback para resposta inesperada %s',
    (body) => {
      const error = new HttpErrorResponse({ status: 503, error: body });
      expect(problemDetail(error, 'Falha.')).toBe('Falha.');
      expect(problemFields(error)).toEqual({});
    },
  );

  it('trata falha fora do HttpClient sem expor objetos', () => {
    expect(problemDetail(new Error('Interno'), 'Falha.')).toBe('Falha.');
    expect(problemFields(new Error('Interno'))).toEqual({});
  });
});
