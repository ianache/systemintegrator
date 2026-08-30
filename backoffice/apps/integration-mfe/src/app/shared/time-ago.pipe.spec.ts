import { TimeAgoPipe } from './time-ago.pipe';

describe('TimeAgoPipe', () => {
  let pipe: TimeAgoPipe;

  beforeEach(() => {
    pipe = new TimeAgoPipe();
    vi.spyOn(Date, 'now').mockReturnValue(new Date('2026-08-30T12:00:00Z').getTime());
  });

  afterEach(() => vi.restoreAllMocks());

  it('returns an em dash for null', () => {
    expect(pipe.transform(null)).toBe('—');
  });

  it('formats minutes', () => {
    expect(pipe.transform('2026-08-30T11:55:00Z')).toBe('hace 5 min');
  });

  it('formats hours', () => {
    expect(pipe.transform('2026-08-30T09:00:00Z')).toBe('hace 3 h');
  });

  it('formats days', () => {
    expect(pipe.transform('2026-08-27T12:00:00Z')).toBe('hace 3 d');
  });

  it('treats sub-minute durations as just now', () => {
    expect(pipe.transform('2026-08-30T11:59:50Z')).toBe('hace un momento');
  });
});
