from __future__ import annotations

import copy
import itertools
import unittest

from scripts.mr3_eval import (
    MINIMUM_RELEASE_GROUP_SIZE,
    _boundary_match_summary,
    _boundaries,
    _sample,
    evaluate,
    release_summary,
)


class BoundaryMatchingTests(unittest.TestCase):
    def test_greedy_counterexample_uses_maximum_cardinality_then_minimum_error(self) -> None:
        self.assertEqual((2, 9), _boundary_match_summary([5, 10], [0, 6], 5))

    def test_dense_and_order_independent_boundaries(self) -> None:
        self.assertEqual((2, 2), _boundary_match_summary([5, 1, 3], [4, 2], 1))
        self.assertEqual(
            _boundary_match_summary([1, 3], [0, 2], 1),
            _boundary_match_summary([3, 1], [2, 0], 1),
        )

    def test_duplicates_are_deduplicated_before_denominators(self) -> None:
        self.assertEqual(([5, 10], 1), _boundaries([10, 5, 5], "test"))
        sample = _sample("sample_unique")
        sample["reference"]["question_boundaries_ms"] = [1100, 1100]
        sample["prediction"]["question_boundaries_ms"] = [1200, 1200]
        result = evaluate([sample])
        metrics = result["aggregate"]
        self.assertEqual(1, metrics["question_boundary_reference_denominator"])
        self.assertEqual(1, metrics["question_boundary_prediction_denominator"])
        self.assertEqual(1, metrics["question_boundary_match_count"])
        self.assertEqual(1, metrics["duplicate_reference_boundaries_removed"])
        self.assertEqual(1, metrics["duplicate_prediction_boundaries_removed"])

    def test_zero_tolerance_and_empty_boundary_denominators(self) -> None:
        self.assertEqual((1, 0), _boundary_match_summary([2], [2], 0))
        self.assertEqual((0, 0), _boundary_match_summary([2], [3], 0))
        sample = _sample("empty_bounds")
        sample["reference"]["question_boundaries_ms"] = []
        sample["prediction"]["question_boundaries_ms"] = []
        metrics = evaluate([sample], tolerance_ms=0)["aggregate"]
        self.assertIsNone(metrics["question_boundary_precision"])
        self.assertIsNone(metrics["question_boundary_recall"])
        self.assertIsNone(metrics["question_boundary_f1"])

    def test_small_point_sets_match_brute_force_cardinality_and_cost(self) -> None:
        def brute_force(reference: tuple[int, ...], prediction: tuple[int, ...], tolerance: int) -> tuple[int, int]:
            def visit(index: int, used: frozenset[int]) -> tuple[int, int]:
                if index == len(reference):
                    return (0, 0)
                best = visit(index + 1, used)
                for candidate_index, point in enumerate(prediction):
                    distance = abs(reference[index] - point)
                    if candidate_index in used or distance > tolerance:
                        continue
                    tail_count, tail_cost = visit(index + 1, used | {candidate_index})
                    proposed = (tail_count + 1, tail_cost + distance)
                    if proposed[0] > best[0] or (proposed[0] == best[0] and proposed[1] < best[1]):
                        best = proposed
                return best

            return visit(0, frozenset())

        point_sets = [tuple(values) for size in range(4) for values in itertools.combinations_with_replacement(range(4), size)]
        for reference in point_sets:
            for prediction in point_sets:
                for tolerance in range(3):
                    with self.subTest(reference=reference, prediction=prediction, tolerance=tolerance):
                        self.assertEqual(
                            brute_force(reference, prediction, tolerance),
                            _boundary_match_summary(list(reference), list(prediction), tolerance),
                        )


class SampleAccountingAndReleaseTests(unittest.TestCase):
    SUPPRESSED = {
        "schema": "pitayaflow.mr3.public-summary.v2",
        "aggregate": {"status": "suppressed"},
        "strata": {},
        "record_flow": {"status": "suppressed"},
        "release_policy": "suppressed_pending_disclosure_review",
    }

    def assert_public_suppressed_without_numbers(self, payload: dict) -> None:
        self.assertEqual(self.SUPPRESSED, payload)
        self.assertEqual(
            {"schema", "aggregate", "strata", "record_flow", "release_policy"},
            set(payload),
        )
        self.assertNotIn("citation_truth_rate", str(payload))
        self.assertNotIn("human_advice_score_mean_0_to_2", str(payload))

    def test_all_rows_for_duplicate_ids_are_excluded_but_distinct_ids_are_kept(self) -> None:
        valid = _sample("same_content_a")
        same_content_new_id = copy.deepcopy(valid)
        same_content_new_id["sample_id"] = "same_content_b"
        duplicate_one = _sample("duplicate_id")
        duplicate_two = copy.deepcopy(duplicate_one)
        invalid = _sample("invalid_consent")
        invalid["consent"]["authorized"] = False

        result = evaluate([valid, same_content_new_id, duplicate_one, duplicate_two, invalid])
        self.assertEqual(5, result["input_record_count"])
        self.assertEqual(2, result["eligible_sample_count"])
        self.assertEqual(3, result["excluded_record_count"])
        self.assertEqual(1, result["duplicate_sample_id_count"])
        self.assertEqual(2, result["duplicate_record_count"])
        self.assertEqual(1, result["invalid_record_count"])
        self.assertEqual(2, result["aggregate"]["samples"])
        self.assertEqual(
            result["input_record_count"],
            result["eligible_sample_count"] + result["excluded_record_count"],
        )
        self.assertEqual(
            result["excluded_record_count"],
            result["duplicate_record_count"] + result["invalid_record_count"],
        )

    def test_small_stratum_suppresses_strata_totals_and_flow(self) -> None:
        samples = []
        for index in range(MINIMUM_RELEASE_GROUP_SIZE):
            strata = ["multi-speaker", "noise"] if index < MINIMUM_RELEASE_GROUP_SIZE - 1 else ["multi-speaker"]
            samples.append(_sample(f"small_cell_{index}", strata))
        audit = evaluate(samples)
        released = release_summary(audit)
        self.assert_public_suppressed_without_numbers(released)
        public_json = str(released)
        self.assertNotIn("small_cell_0", public_json)
        self.assertNotIn("I led the migration", public_json)

    def test_small_overlap_cell_is_suppressed_even_when_each_margin_is_large(self) -> None:
        samples = []
        for index in range(MINIMUM_RELEASE_GROUP_SIZE):
            samples.append(_sample(f"noise_only_{index}", ["noise"]))
            samples.append(_sample(f"clear_only_{index}", ["clear"]))
        samples.append(_sample("small_overlap", ["noise", "clear"]))
        audit = evaluate(samples)
        self.assertEqual(MINIMUM_RELEASE_GROUP_SIZE + 1, audit["strata"]["noise"]["samples"])
        self.assertEqual(MINIMUM_RELEASE_GROUP_SIZE + 1, audit["strata"]["clear"]["samples"])
        self.assertEqual(1, audit["stratum_cells"]["clear|noise"]["samples"])
        self.assertEqual(
            1,
            audit["sample_contributions"]["stratum_cells"]["clear|noise"]["citation_truth_rate"]["contributors"],
        )
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_sparse_metric_count_suppresses_otherwise_large_group(self) -> None:
        samples = [_sample(f"sparse_metric_{index}") for index in range(MINIMUM_RELEASE_GROUP_SIZE)]
        for sample in samples[1:]:
            sample["prediction"]["citations"] = []
        audit = evaluate(samples)
        self.assertEqual(MINIMUM_RELEASE_GROUP_SIZE, audit["aggregate"]["samples"])
        self.assertEqual(1, audit["aggregate"]["citation_denominator"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_one_sample_with_five_citations_cannot_supply_k_contributors(self) -> None:
        samples = [_sample(f"one_many_{index}") for index in range(5)]
        samples[0]["prediction"]["citations"] *= 5
        for sample in samples[1:]:
            sample["prediction"]["citations"] = []
        audit = evaluate(samples)
        contribution = audit["sample_contributions"]["aggregate"]["citation_truth_rate"]
        self.assertEqual(5, audit["disclosure_counts"]["aggregate"]["citations"])
        self.assertEqual(1, contribution["contributors"])
        self.assertEqual(4, 5 - contribution["contributors"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_five_distinct_samples_each_contribute_and_release(self) -> None:
        samples = [_sample(f"five_people_{index}") for index in range(5)]
        audit = evaluate(samples)
        contribution = audit["sample_contributions"]["aggregate"]["citation_truth_rate"]
        self.assertEqual(5, contribution["contributors"])
        self.assertEqual(5, contribution["success_samples"])
        self.assertEqual(0, contribution["failure_samples"])
        self.assertEqual("released", release_summary(audit)["aggregate"]["status"])

    def test_one_anomalous_sample_suppresses_a_safe_large_group(self) -> None:
        samples = [_sample(f"one_bad_{index}") for index in range(10)]
        for sample in samples:
            sample["prediction"]["citations"] *= 5
        for citation in samples[0]["prediction"]["citations"]:
            citation["quote"] = "forged"
        audit = evaluate(samples)
        contribution = audit["sample_contributions"]["aggregate"]["citation_truth_rate"]
        self.assertEqual(1, contribution["failure_samples"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_one_missing_sample_suppresses_a_safe_large_group(self) -> None:
        samples = [_sample(f"one_missing_{index}") for index in range(10)]
        for sample in samples:
            sample["prediction"]["citations"] *= 5
        samples[0]["prediction"]["citations"] = []
        audit = evaluate(samples)
        contribution = audit["sample_contributions"]["aggregate"]["citation_truth_rate"]
        self.assertEqual(9, contribution["contributors"])
        self.assertEqual(1, 10 - contribution["contributors"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_one_person_many_ratings_does_not_meet_sample_k(self) -> None:
        samples = [_sample(f"rating_owner_{index}") for index in range(10)]
        for index, sample in enumerate(samples):
            sample["human_reviews"] = []
            if index == 0:
                sample["human_reviews"] = [
                    {"reviewer_id": f"reviewer_{rating:02d}", "ratings": {"rec1": 2}}
                    for rating in range(5)
                ]
        audit = evaluate(samples)
        contribution = audit["sample_contributions"]["aggregate"]["human_advice_score_mean_0_to_2"]
        self.assertEqual(5, audit["disclosure_counts"]["aggregate"]["human_ratings"])
        self.assertEqual(1, contribution["contributors"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_one_person_many_recommendations_does_not_meet_sample_k(self) -> None:
        samples = [_sample(f"recommendation_owner_{index}") for index in range(5)]
        for sample in samples:
            sample["human_reviews"] = []
            sample["prediction"]["recommendations"] = []
        samples[0]["prediction"]["recommendations"] = [
            {"id": f"rec{index}", "text": "Synthetic suggestion"} for index in range(5)
        ]
        audit = evaluate(samples)
        contribution = audit["sample_contributions"]["aggregate"]["recommendation_item_count"]
        self.assertEqual(5, audit["disclosure_counts"]["aggregate"]["recommendation_items"])
        self.assertEqual(1, contribution["contributors"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_single_sample_score_pattern_is_suppressed_even_with_many_ratings(self) -> None:
        samples = [_sample(f"score_pattern_{index}") for index in range(10)]
        for index, sample in enumerate(samples):
            sample["human_reviews"] = [
                {"reviewer_id": f"reviewer_a_{index:02d}", "ratings": {"rec1": 2}},
                {"reviewer_id": f"reviewer_b_{index:02d}", "ratings": {"rec1": 2}},
            ]
        samples[0]["human_reviews"] = [
            *samples[0]["human_reviews"],
            *[
                {"reviewer_id": f"reviewer_zero_{rating:02d}", "ratings": {"rec1": 0}}
                for rating in range(3)
            ],
        ]
        audit = evaluate(samples)
        contribution = audit["sample_contributions"]["aggregate"]["human_rating_pattern_101"]
        self.assertEqual(3, audit["disclosure_counts"]["aggregate"]["human_rating_score_0_count"])
        self.assertEqual(1, contribution["contributors"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_score_bands_contributed_by_five_distinct_samples_are_safe(self) -> None:
        samples = [_sample(f"score_people_{index}") for index in range(5)]
        for sample in samples:
            sample["human_reviews"] = [
                {"reviewer_id": "reviewer_a", "ratings": {"rec1": 0}},
                {"reviewer_id": "reviewer_b", "ratings": {"rec1": 2}},
            ]
        audit = evaluate(samples)
        contributions = audit["sample_contributions"]["aggregate"]
        self.assertEqual(5, contributions["human_rating_score_0_count"]["contributors"])
        self.assertEqual(5, contributions["human_rating_score_2_count"]["contributors"])
        self.assertEqual(5, contributions["human_rating_pattern_101"]["contributors"])
        self.assertEqual("released", release_summary(audit)["aggregate"]["status"])

    def test_one_citation_hit_among_five_is_suppressed_from_raw_counts(self) -> None:
        samples = [_sample(f"one_hit_{index}") for index in range(5)]
        for sample in samples[1:]:
            sample["prediction"]["citations"][0]["quote"] = "forged"
        audit = evaluate(samples)
        counts = audit["disclosure_counts"]["aggregate"]
        self.assertEqual(5, counts["citations"])
        self.assertEqual(1, counts["truthful_citations"])
        self.assertEqual(4, counts["citations"] - counts["truthful_citations"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_one_citation_miss_among_five_is_suppressed_by_complement(self) -> None:
        samples = [_sample(f"one_miss_{index}") for index in range(5)]
        samples[-1]["prediction"]["citations"][0]["quote"] = "forged"
        audit = evaluate(samples)
        counts = audit["disclosure_counts"]["aggregate"]
        self.assertEqual(5, counts["citations"])
        self.assertEqual(4, counts["truthful_citations"])
        self.assertEqual(1, counts["citations"] - counts["truthful_citations"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_single_rating_band_member_suppresses_mean_and_agreement(self) -> None:
        samples = [_sample(f"sparse_score_{index}") for index in range(5)]
        for sample in samples:
            sample["human_reviews"][0]["ratings"]["rec1"] = 2
            sample["human_reviews"][1]["ratings"]["rec1"] = 2
        samples[0]["human_reviews"][0]["ratings"]["rec1"] = 0
        audit = evaluate(samples)
        counts = audit["disclosure_counts"]["aggregate"]
        self.assertEqual(1, counts["human_rating_score_0_count"])
        self.assertEqual(9, counts["human_rating_score_2_count"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_one_excluded_record_suppresses_record_flow_and_all_metrics(self) -> None:
        samples = [_sample(f"flow_valid_{index}") for index in range(5)]
        rejected = _sample("flow_rejected")
        rejected["consent"]["authorized"] = False
        audit = evaluate([*samples, rejected])
        self.assertEqual(1, audit["invalid_record_count"])
        self.assertEqual(6, audit["input_record_count"])
        self.assert_public_suppressed_without_numbers(release_summary(audit))

    def test_large_cells_release_aggregates_and_flow(self) -> None:
        samples = [_sample(f"release_{index}") for index in range(MINIMUM_RELEASE_GROUP_SIZE)]
        audit = evaluate(samples)
        raw_buckets = audit["disclosure_counts"]
        for bucket in [raw_buckets["aggregate"], *raw_buckets["strata"].values(), *raw_buckets["stratum_cells"].values()]:
            self.assertFalse(any(0 < value < MINIMUM_RELEASE_GROUP_SIZE for value in bucket.values()))
        released = release_summary(audit)
        self.assertEqual("pitayaflow.mr3.public-summary.v2", released["schema"])
        self.assertEqual(MINIMUM_RELEASE_GROUP_SIZE, released["aggregate"]["samples"])
        self.assertEqual("released", released["record_flow"]["status"])
        self.assertEqual(MINIMUM_RELEASE_GROUP_SIZE, released["record_flow"]["eligible_sample_count"])
        self.assertEqual(MINIMUM_RELEASE_GROUP_SIZE, released["strata"]["noise"]["samples"])
        self.assertNotIn("disclosure_counts", released)
        self.assertNotIn("stratum_cells", released)
        self.assertNotIn("release_0", str(released))
        self.assertNotIn("I led the migration", str(released))

    def test_old_or_incomplete_audit_suppresses_without_numeric_metrics(self) -> None:
        self.assert_public_suppressed_without_numbers(release_summary({"aggregate": {"samples": 100}}))
        safe_audit = evaluate([_sample(f"safe_legacy_{index}") for index in range(5)])
        safe_audit["schema"] = "pitayaflow.mr3.restricted-audit.v3"
        self.assert_public_suppressed_without_numbers(release_summary(safe_audit))
        safe_audit = evaluate([_sample(f"missing_contrib_{index}") for index in range(5)])
        safe_audit.pop("sample_contributions")
        self.assert_public_suppressed_without_numbers(release_summary(safe_audit))

    def test_inconsistent_sample_contribution_counters_fail_closed(self) -> None:
        audit = evaluate([_sample(f"counter_check_{index}") for index in range(5)])
        audit["sample_contributions"]["aggregate"]["citation_truth_rate"]["contributors"] = 0
        self.assert_public_suppressed_without_numbers(release_summary(audit))


if __name__ == "__main__":
    unittest.main()
