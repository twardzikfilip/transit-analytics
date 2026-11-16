import { ScheduledRun } from '../../core/models/models';

/** What is left of a brigade's day at `now`: the run under way (marked), then the runs still to come. */
export function remainingRuns(
  runs: ScheduledRun[],
  now: Date,
): { run: ScheduledRun; now: boolean }[] {
  const t = now.getTime();
  return runs
    .filter((run) => Date.parse(run.arrival) > t)
    .map((run) => ({ run, now: Date.parse(run.departure) <= t }));
}
