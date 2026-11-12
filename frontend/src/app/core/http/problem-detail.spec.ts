import { HttpErrorResponse } from '@angular/common/http';
import { problemDetail, problemFields } from './problem-detail';

describe('Administrative ProblemDetail', () => {
  it('extracts detail and the fields map from the actual contract', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { detail: 'Review the fields.', fields: { name: 'Invalid name.' } },
    });
    expect(problemDetail(error, 'Failure.')).toBe('Review the fields.');
    expect(problemFields(error)).toEqual({ name: 'Invalid name.' });
  });

  it.each([null, 'Error', {}, { detail: ' ' }, { detail: 12 }, { fields: [] }, { fields: null }])(
    'uses fallback for unexpected response %s',
    (body) => {
      const error = new HttpErrorResponse({ status: 503, error: body });
      expect(problemDetail(error, 'Failure.')).toBe('Failure.');
      expect(problemFields(error)).toEqual({});
    },
  );

  it('handles failures outside HttpClient without exposing objects', () => {
    expect(problemDetail(new Error('Internal'), 'Failure.')).toBe('Failure.');
    expect(problemFields(new Error('Internal'))).toEqual({});
  });
});
