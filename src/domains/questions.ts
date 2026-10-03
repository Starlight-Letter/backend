export interface Question {
  id: string;
  bridgeId: string;
  body: string;
  effectiveAt: string;
  status: 'published' | 'draft';
}

export function selectCurrentQuestion(
  questions: readonly Question[],
  bridgeId: string,
  now: Date,
): Question | null {
  return questions
    .filter((question) =>
      question.bridgeId === bridgeId &&
      question.status === 'published' &&
      Date.parse(question.effectiveAt) <= now.getTime(),
    )
    .sort((a, b) =>
      Date.parse(b.effectiveAt) - Date.parse(a.effectiveAt) || b.id.localeCompare(a.id),
    )[0] ?? null;
}

export function nextMondayStartKst(now: Date): Date {
  const kstOffsetMs = 9 * 60 * 60 * 1000;
  const local = new Date(now.getTime() + kstOffsetMs);
  const daysUntilMonday = (8 - local.getUTCDay()) % 7 || 7;
  const nextMondayLocal = Date.UTC(
    local.getUTCFullYear(),
    local.getUTCMonth(),
    local.getUTCDate() + daysUntilMonday,
  );
  return new Date(nextMondayLocal - kstOffsetMs);
}
