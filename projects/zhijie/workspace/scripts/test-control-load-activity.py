import runpy
import unittest
from pathlib import Path


auditor = runpy.run_path(str(Path(__file__).with_name("audit-control-load.py")))
audit = auditor["audit"]


class ActivityTest(unittest.TestCase):
    def setUp(self):
        self.rows = []
        for elapsed in (0, 25, 45, 68, 89):
            for actor in range(100):
                for operation in auditor["CORE"]:
                    if actor >= 90 and operation == "heartbeat" and elapsed < 68:
                        continue
                    self.rows.append(dict(actor=actor, operation=operation, code=None, status=200,
                                          workloadElapsedMs=elapsed * 1000))
        self.summary = dict(accounts=100, scope="local-control-plane-smoke", requestedSeconds=90, durationSeconds=90,
                            execution="completed", requests=len(self.rows), rotationsPerAccountMin=0)

    def test_observed_activity_with_planned_presence_pause(self):
        self.assertEqual(audit(self.summary, self.rows, 90)["maxCoreGapSeconds"], 25)

    def test_global_traffic_cannot_hide_one_stalled_writer(self):
        for row in self.rows:
            if row["actor"] == 23 and row["operation"] == "message-write" and row["workloadElapsedMs"] in (25000, 45000):
                row["actor"] = 24
        with self.assertRaisesRegex(ValueError, "activity gap .*actor=23, operation=message-write"):
            audit(self.summary, self.rows, 90)

    def test_disappearing_account_is_not_a_pass(self):
        for row in self.rows:
            if row["actor"] == 91 and row["operation"] == "message-read" and row["workloadElapsedMs"] not in (0, 25000):
                row["actor"] = 92
        with self.assertRaisesRegex(ValueError, "missing activity near end"):
            audit(self.summary, self.rows, 90)

    def test_mismatched_summary_and_wrong_requested_duration(self):
        with self.assertRaisesRegex(ValueError, "differs from summary"):
            audit({**self.summary, "requests": 0}, self.rows, 90)
        with self.assertRaisesRegex(ValueError, "requested duration"):
            audit(self.summary, self.rows, 3600)

    def test_failure_and_unfinished_run(self):
        with self.assertRaisesRegex(ValueError, "failed operation"):
            audit(self.summary, [{**self.rows[0], "code": "HTTP_FAILED"}], 90)
        with self.assertRaisesRegex(ValueError, "completed local run"):
            audit({**self.summary, "execution": "failed"}, self.rows, 90)

    def test_missing_or_late_first_sample_is_not_a_new_time_origin(self):
        for offset in (None, True, float('nan'), float('inf'), -1):
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                audit(self.summary, [{**self.rows[0], "workloadElapsedMs": offset}], 90)
        with self.assertRaisesRegex(ValueError, 'activity gap'):
            audit(self.summary, [{**self.rows[0], "workloadElapsedMs": 61000}], 90)

    def test_summary_cannot_hide_long_tail_or_reversed_refresh(self):
        with self.assertRaisesRegex(ValueError, 'missing activity near end'):
            audit({**self.summary, 'durationSeconds': 151}, self.rows, 90)
        with self.assertRaisesRegex(ValueError, 'ordered workload'):
            audit(self.summary, [*self.rows, {**self.rows[0], 'operation': 'session-refresh'}], 90)

    def test_hour_requires_four_rotations_for_each_account(self):
        rows = []
        for second in range(0, 3601, 30):
            for actor in range(100):
                for operation in auditor['CORE']:
                    if actor >= 90 and operation == 'heartbeat' and second < 68:
                        continue
                    rows.append(dict(actor=actor, operation=operation, code=None, status=200, workloadElapsedMs=second * 1000))
                if second in (600, 1500, 2400, 3300):
                    rows.append(dict(actor=actor, operation='session-refresh', code=None, status=200, workloadElapsedMs=second * 1000))
        summary = {**self.summary, 'requestedSeconds': 3600, 'durationSeconds': 3600, 'requests': len(rows), 'rotationsPerAccountMin': 4}
        self.assertEqual(audit(summary, rows, 3600)['minimumRefreshes'], 4)
        next(row for row in rows if row['operation'] == 'session-refresh' and row['actor'] == 11)['actor'] = 12
        with self.assertRaisesRegex(ValueError, 'four refreshes required'):
            audit({**summary, 'rotationsPerAccountMin': 3}, rows, 3600)


if __name__ == "__main__":
    unittest.main()
