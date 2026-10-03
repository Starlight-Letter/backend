import { describe, expect, it } from 'vitest';
import { nextMondayStartKst, selectCurrentQuestion, type Question } from './questions';

const question = (
  id: string,
  bridgeId: string,
  effectiveAt: string,
  status: Question['status'] = 'published',
): Question => ({ id, bridgeId, effectiveAt, status, body: `질문 ${id}` });

describe('current question', () => {
  it('switches at Monday 00:00 KST without a deployment', () => {
    const questions = [
      question('old', 'sillim', '2026-09-27T15:00:00.000Z'),
      question('new', 'sillim', '2026-10-04T15:00:00.000Z'),
    ];
    expect(selectCurrentQuestion(questions, 'sillim', new Date('2026-10-04T14:59:59.999Z'))?.id).toBe('old');
    expect(selectCurrentQuestion(questions, 'sillim', new Date('2026-10-04T15:00:00.000Z'))?.id).toBe('new');
  });

  it('continues the previous question when no new week is registered', () => {
    const questions = [question('old', 'bongnim', '2026-09-27T15:00:00.000Z')];
    expect(selectCurrentQuestion(questions, 'bongnim', new Date('2026-10-11T15:00:00.000Z'))?.id).toBe('old');
  });

  it('does not show another bridge or an unpublished question', () => {
    const questions = [
      question('other', 'bongnim', '2026-10-04T15:00:00.000Z'),
      question('draft', 'sillim', '2026-10-04T15:00:00.000Z', 'draft'),
    ];
    expect(selectCurrentQuestion(questions, 'sillim', new Date('2026-10-05T00:00:00.000Z'))).toBeNull();
  });

  it('applies a late current-week registration immediately', () => {
    const questions = [question('late', 'sillim2', '2026-10-06T03:00:00.000Z')];
    expect(selectCurrentQuestion(questions, 'sillim2', new Date('2026-10-06T03:00:00.000Z'))?.id).toBe('late');
  });
});

describe('weekly scheduling', () => {
  it('returns next Monday midnight in KST as a UTC instant', () => {
    const result = nextMondayStartKst(new Date('2026-10-02T01:00:00.000Z'));
    expect(result.toISOString()).toBe('2026-10-04T15:00:00.000Z');
  });

  it('does not schedule the current Monday after it has started', () => {
    const result = nextMondayStartKst(new Date('2026-10-04T15:00:00.000Z'));
    expect(result.toISOString()).toBe('2026-10-11T15:00:00.000Z');
  });
});
