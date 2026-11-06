#!/usr/bin/env python3
"""Offline MR3 evaluator with exact restricted audit and small-cell-safe release."""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PSEUDONYM = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
ALLOWED_STRATA = frozenset({
    "single-speaker", "multi-speaker", "overlap", "noise", "clear", "low-snr",
    "language-zh", "language-en",
})
MINIMUM_RELEASE_GROUP_SIZE = 5
MAX_BOUNDARIES_PER_SAMPLE = 500
SAMPLE_CONTRIBUTION_METRICS = (
    "utterance_coverage",
    "speaker_pair_accuracy",
    "timing_within_tolerance_rate",
    "question_boundary_reference",
    "question_boundary_prediction",
    "question_boundary_f1",
    "question_boundary_mean_absolute_error_ms",
    "duplicate_reference_boundaries_removed",
    "duplicate_prediction_boundaries_removed",
    "citation_truth_rate",
    "recommendation_item_count",
    "human_advice_score_mean_0_to_2",
    "human_rating_score_0_count",
    "human_rating_score_1_count",
    "human_rating_score_2_count",
    "human_rating_pattern_000",
    "human_rating_pattern_001",
    "human_rating_pattern_010",
    "human_rating_pattern_011",
    "human_rating_pattern_100",
    "human_rating_pattern_101",
    "human_rating_pattern_110",
    "human_rating_pattern_111",
    "human_exact_agreement_rate",
    "human_within_one_agreement_rate",
)


class RecordError(ValueError):
    pass


def _text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise RecordError(f"invalid {field}")
    return value


def _time(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise RecordError(f"invalid {field}")
    return value


def _normal_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _parse_expiry(value: Any) -> datetime:
    if not isinstance(value, str):
        raise RecordError("invalid consent expiry")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise RecordError("invalid consent expiry") from exc
    if parsed.tzinfo is None:
        raise RecordError("consent expiry must include timezone")
    return parsed.astimezone(timezone.utc)


def _boundaries(value: Any, label: str) -> tuple[list[int], int]:
    if not isinstance(value, list):
        raise RecordError(f"{label} must be a list")
    points = [_time(item, label) for item in value]
    if len(points) > MAX_BOUNDARIES_PER_SAMPLE:
        raise RecordError(f"too many {label}")
    unique = sorted(set(points))
    return unique, len(points) - len(unique)


def _boundary_match_summary(reference: list[int], prediction: list[int], tolerance_ms: int) -> tuple[int, int]:
    """Return max one-to-one matches, then min total absolute error.

    Sorted one-dimensional absolute-distance matching has an order-preserving
    optimum. The dynamic program maximizes cardinality first, then minimizes
    distance. Fixed iteration and tie precedence make the result deterministic.
    Memory is O(len(prediction)); time is O(len(reference) * len(prediction)).
    """
    refs = sorted(reference)
    preds = sorted(prediction)
    previous = [(0, 0)] * (len(preds) + 1)

    def better(left: tuple[int, int], right: tuple[int, int]) -> tuple[int, int]:
        if left[0] != right[0]:
            return left if left[0] > right[0] else right
        if left[1] != right[1]:
            return left if left[1] < right[1] else right
        return left

    for ref_point in refs:
        current = [(0, 0)] * (len(preds) + 1)
        for column, pred_point in enumerate(preds, 1):
            best = better(previous[column], current[column - 1])
            distance = abs(ref_point - pred_point)
            if distance <= tolerance_ms:
                matched = previous[column - 1]
                best = better(best, (matched[0] + 1, matched[1] + distance))
            current[column] = best
        previous = current
    return previous[-1]


def _matches(reference: list[int], prediction: list[int], tolerance_ms: int) -> int:
    """Compatibility helper returning only the maximum matched boundary count."""
    return _boundary_match_summary(reference, prediction, tolerance_ms)[0]


def _validate_utterances(value: Any, label: str) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise RecordError(f"{label} utterances must be a list")
    result: list[dict[str, Any]] = []
    ids: set[str] = set()
    for item in value:
        if not isinstance(item, dict):
            raise RecordError(f"{label} utterance must be an object")
        utterance_id = _text(item.get("id"), f"{label} utterance id")
        if utterance_id in ids:
            raise RecordError(f"duplicate {label} utterance id")
        ids.add(utterance_id)
        speaker = _text(item.get("speaker"), f"{label} speaker")
        text = _text(item.get("text"), f"{label} text")
        start = _time(item.get("start_ms"), f"{label} start_ms")
        end = _time(item.get("end_ms"), f"{label} end_ms")
        if end <= start:
            raise RecordError(f"{label} utterance interval must be positive")
        reference_id = item.get("reference_id") if label == "prediction" else None
        if reference_id is not None and not isinstance(reference_id, str):
            raise RecordError("prediction reference_id must be a string")
        question_id = item.get("question_id")
        if question_id is not None and not isinstance(question_id, str):
            raise RecordError("question_id must be a string")
        result.append({
            "id": utterance_id, "speaker": speaker, "text": text,
            "start_ms": start, "end_ms": end,
            "reference_id": reference_id, "question_id": question_id,
        })
    return result


def validate_record(record: Any, now: datetime | None = None) -> tuple[dict[str, Any], tuple[str, ...]]:
    if not isinstance(record, dict):
        raise RecordError("record must be an object")
    sample_id = record.get("sample_id")
    if not isinstance(sample_id, str) or not PSEUDONYM.fullmatch(sample_id):
        raise RecordError("sample_id must be a pseudonymous token")

    consent = record.get("consent")
    if not isinstance(consent, dict) or consent.get("authorized") is not True:
        raise RecordError("sample is not authorized")
    if consent.get("scope") != "offline-evaluation":
        raise RecordError("consent scope must be offline-evaluation")
    _text(consent.get("authorization_ref"), "authorization_ref")
    expiry = _parse_expiry(consent.get("expires_at"))
    if expiry <= (now or datetime.now(timezone.utc)):
        raise RecordError("consent expired")

    strata = record.get("strata")
    if not isinstance(strata, list) or not strata:
        raise RecordError("at least one stratum is required")
    normalized_strata: list[str] = []
    for item in strata:
        if not isinstance(item, str) or item not in ALLOWED_STRATA:
            raise RecordError("stratum is not in the approved coarse taxonomy")
        if item not in normalized_strata:
            normalized_strata.append(item)

    reference = record.get("reference")
    prediction = record.get("prediction")
    if not isinstance(reference, dict) or not isinstance(prediction, dict):
        raise RecordError("reference and prediction objects are required")
    ref_utterances = _validate_utterances(reference.get("utterances"), "reference")
    pred_utterances = _validate_utterances(prediction.get("utterances"), "prediction")
    ref_by_id = {item["id"]: item for item in ref_utterances}
    pred_by_ref = {item["reference_id"]: item for item in pred_utterances if item.get("reference_id")}
    if len(pred_by_ref) != sum(1 for item in pred_utterances if item.get("reference_id")):
        raise RecordError("prediction reference_id values must be unique")

    ref_bounds, ref_boundary_duplicates = _boundaries(reference.get("question_boundaries_ms", []), "reference boundaries")
    pred_bounds, pred_boundary_duplicates = _boundaries(prediction.get("question_boundaries_ms", []), "prediction boundaries")
    citations = prediction.get("citations", [])
    if not isinstance(citations, list):
        raise RecordError("citations must be a list")
    for item in citations:
        if not isinstance(item, dict):
            raise RecordError("citation must be an object")
        _text(item.get("quote"), "citation quote")
        _text(item.get("reference_utterance_id"), "citation reference_utterance_id")
        _text(item.get("speaker"), "citation speaker")
        start = _time(item.get("start_ms"), "citation start_ms")
        end = _time(item.get("end_ms"), "citation end_ms")
        if end <= start:
            raise RecordError("citation interval must be positive")

    recommendations = prediction.get("recommendations", [])
    if not isinstance(recommendations, list):
        raise RecordError("recommendations must be a list")
    rec_ids: set[str] = set()
    for item in recommendations:
        if not isinstance(item, dict):
            raise RecordError("recommendation must be an object")
        rec_id = _text(item.get("id"), "recommendation id")
        _text(item.get("text"), "recommendation text")
        if rec_id in rec_ids:
            raise RecordError("recommendation IDs must be unique")
        rec_ids.add(rec_id)

    reviews = record.get("human_reviews", [])
    if not isinstance(reviews, list):
        raise RecordError("human_reviews must be a list")
    reviewer_ids: set[str] = set()
    for review in reviews:
        if not isinstance(review, dict):
            raise RecordError("human review must be an object")
        reviewer_id = review.get("reviewer_id")
        if not isinstance(reviewer_id, str) or not PSEUDONYM.fullmatch(reviewer_id):
            raise RecordError("reviewer_id must be pseudonymous")
        if reviewer_id in reviewer_ids:
            raise RecordError("reviewer IDs must be unique per sample")
        reviewer_ids.add(reviewer_id)
        ratings = review.get("ratings")
        if not isinstance(ratings, dict):
            raise RecordError("review ratings must be an object")
        if not set(ratings).issubset(rec_ids):
            raise RecordError("review references an unknown recommendation")
        if any(isinstance(value, bool) or value not in (0, 1, 2) for value in ratings.values()):
            raise RecordError("ratings must be 0, 1, or 2")

    normalized = {
        "ref": ref_by_id,
        "pred": pred_by_ref,
        "ref_boundaries": ref_bounds,
        "pred_boundaries": pred_bounds,
        "ref_boundary_duplicates": ref_boundary_duplicates,
        "pred_boundary_duplicates": pred_boundary_duplicates,
        "citations": citations,
        "recommendations": rec_ids,
        "reviews": reviews,
    }
    return normalized, tuple(normalized_strata)


def _new_bucket() -> dict[str, Any]:
    return {
        "samples": 0,
        "reference_utterances": 0,
        "matched_utterances": 0,
        "speaker_pairs": 0,
        "speaker_pair_correct": 0,
        "timed_utterances": 0,
        "timing_within_tolerance": 0,
        "reference_question_boundaries": 0,
        "prediction_question_boundaries": 0,
        "matched_question_boundaries": 0,
        "question_boundary_error_sum_ms": 0,
        "duplicate_reference_boundaries_removed": 0,
        "duplicate_prediction_boundaries_removed": 0,
        "citations": 0,
        "truthful_citations": 0,
        "recommendation_items": 0,
        "human_ratings": 0,
        "human_rating_score_0_count": 0,
        "human_rating_score_1_count": 0,
        "human_rating_score_2_count": 0,
        "human_score_sum": 0,
        "exact_rating_pairs": 0,
        "comparable_rating_pairs": 0,
        "rating_pairs_within_one": 0,
    }


def _new_sample_contribution_bucket() -> dict[str, dict[str, int]]:
    return {
        metric: {"contributors": 0, "success_samples": 0, "failure_samples": 0, "both_samples": 0}
        for metric in SAMPLE_CONTRIBUTION_METRICS
    }


def _add_sample_contributions(
    bucket: dict[str, dict[str, int]],
    outcomes: dict[str, tuple[bool, bool, bool]],
) -> None:
    for metric in SAMPLE_CONTRIBUTION_METRICS:
        contributes, success, failure = outcomes[metric]
        counts = bucket[metric]
        counts["contributors"] += int(contributes)
        counts["success_samples"] += int(success)
        counts["failure_samples"] += int(failure)
        counts["both_samples"] += int(success and failure)


def _add_record(
    bucket: dict[str, Any], data: dict[str, Any], tolerance_ms: int
) -> dict[str, tuple[bool, bool, bool]]:
    before = dict(bucket)
    ref: dict[str, dict[str, Any]] = data["ref"]
    pred: dict[str, dict[str, Any]] = data["pred"]
    matched_ids = [key for key in ref if key in pred]
    bucket["samples"] += 1
    bucket["reference_utterances"] += len(ref)
    bucket["matched_utterances"] += len(matched_ids)

    for i, left_id in enumerate(matched_ids):
        for right_id in matched_ids[i + 1:]:
            ref_same = ref[left_id]["speaker"] == ref[right_id]["speaker"]
            pred_same = pred[left_id]["speaker"] == pred[right_id]["speaker"]
            bucket["speaker_pairs"] += 1
            bucket["speaker_pair_correct"] += int(ref_same == pred_same)

    for ref_id in matched_ids:
        expected, actual = ref[ref_id], pred[ref_id]
        bucket["timed_utterances"] += 1
        bucket["timing_within_tolerance"] += int(
            abs(expected["start_ms"] - actual["start_ms"]) <= tolerance_ms
            and abs(expected["end_ms"] - actual["end_ms"]) <= tolerance_ms
        )

    ref_boundaries = data["ref_boundaries"]
    pred_boundaries = data["pred_boundaries"]
    matches, error_sum = _boundary_match_summary(ref_boundaries, pred_boundaries, tolerance_ms)
    bucket["reference_question_boundaries"] += len(ref_boundaries)
    bucket["prediction_question_boundaries"] += len(pred_boundaries)
    bucket["matched_question_boundaries"] += matches
    bucket["question_boundary_error_sum_ms"] += error_sum
    bucket["duplicate_reference_boundaries_removed"] += data["ref_boundary_duplicates"]
    bucket["duplicate_prediction_boundaries_removed"] += data["pred_boundary_duplicates"]

    for citation in data["citations"]:
        bucket["citations"] += 1
        source = ref.get(citation["reference_utterance_id"])
        if not source:
            continue
        quote = _normal_text(citation["quote"])
        source_text = _normal_text(source["text"])
        truthful = (
            bool(quote)
            and quote in source_text
            and citation["speaker"] == source["speaker"]
            and citation["start_ms"] >= source["start_ms"] - tolerance_ms
            and citation["end_ms"] <= source["end_ms"] + tolerance_ms
        )
        bucket["truthful_citations"] += int(truthful)

    bucket["recommendation_items"] += len(data["recommendations"])
    ratings_by_rec: dict[str, list[int]] = defaultdict(list)
    for review in data["reviews"]:
        for rec_id, score in review["ratings"].items():
            ratings_by_rec[rec_id].append(score)
            bucket["human_ratings"] += 1
            bucket[f"human_rating_score_{score}_count"] += 1
            bucket["human_score_sum"] += score
    for scores in ratings_by_rec.values():
        if len(scores) >= 2:
            bucket["comparable_rating_pairs"] += 1
            bucket["exact_rating_pairs"] += int(len(set(scores)) == 1)
            bucket["rating_pairs_within_one"] += int(max(scores) - min(scores) <= 1)

    delta = {key: bucket[key] - before[key] for key in bucket}

    def outcome(denominator: int, success: bool = False, failure: bool = False) -> tuple[bool, bool, bool]:
        return denominator > 0, denominator > 0 and success, denominator > 0 and failure

    reference_count = delta["reference_question_boundaries"]
    prediction_count = delta["prediction_question_boundaries"]
    matched_boundaries = delta["matched_question_boundaries"]
    false_negative_boundaries = reference_count - matched_boundaries
    false_positive_boundaries = prediction_count - matched_boundaries
    citation_misses = delta["citations"] - delta["truthful_citations"]
    exact_misses = delta["comparable_rating_pairs"] - delta["exact_rating_pairs"]
    within_one_misses = delta["comparable_rating_pairs"] - delta["rating_pairs_within_one"]
    raw_reference_boundaries = reference_count + delta["duplicate_reference_boundaries_removed"]
    raw_prediction_boundaries = prediction_count + delta["duplicate_prediction_boundaries_removed"]
    outcomes = {
        "utterance_coverage": outcome(
            delta["reference_utterances"],
            delta["matched_utterances"] > 0,
            delta["reference_utterances"] - delta["matched_utterances"] > 0,
        ),
        "speaker_pair_accuracy": outcome(
            delta["speaker_pairs"],
            delta["speaker_pair_correct"] > 0,
            delta["speaker_pairs"] - delta["speaker_pair_correct"] > 0,
        ),
        "timing_within_tolerance_rate": outcome(
            delta["timed_utterances"],
            delta["timing_within_tolerance"] > 0,
            delta["timed_utterances"] - delta["timing_within_tolerance"] > 0,
        ),
        "question_boundary_reference": outcome(
            raw_reference_boundaries,
            matched_boundaries > 0, false_negative_boundaries > 0,
        ),
        "question_boundary_prediction": outcome(
            raw_prediction_boundaries,
            matched_boundaries > 0, false_positive_boundaries > 0,
        ),
        "question_boundary_f1": outcome(
            min(reference_count, prediction_count),
            matched_boundaries > 0,
            false_negative_boundaries > 0 or false_positive_boundaries > 0,
        ),
        "question_boundary_mean_absolute_error_ms": outcome(
            matched_boundaries,
            delta["question_boundary_error_sum_ms"] == 0,
            delta["question_boundary_error_sum_ms"] > 0,
        ),
        "duplicate_reference_boundaries_removed": outcome(
            raw_reference_boundaries,
            delta["duplicate_reference_boundaries_removed"] == 0,
            delta["duplicate_reference_boundaries_removed"] > 0,
        ),
        "duplicate_prediction_boundaries_removed": outcome(
            raw_prediction_boundaries,
            delta["duplicate_prediction_boundaries_removed"] == 0,
            delta["duplicate_prediction_boundaries_removed"] > 0,
        ),
        "citation_truth_rate": outcome(
            delta["citations"],
            delta["truthful_citations"] > 0, citation_misses > 0,
        ),
        "recommendation_item_count": outcome(delta["recommendation_items"]),
        "human_advice_score_mean_0_to_2": outcome(
            delta["human_ratings"]
        ),
        "human_rating_score_0_count": outcome(
            delta["human_rating_score_0_count"]
        ),
        "human_rating_score_1_count": outcome(
            delta["human_rating_score_1_count"]
        ),
        "human_rating_score_2_count": outcome(
            delta["human_rating_score_2_count"]
        ),
        "human_exact_agreement_rate": outcome(
            delta["comparable_rating_pairs"],
            delta["exact_rating_pairs"] > 0, exact_misses > 0,
        ),
        "human_within_one_agreement_rate": outcome(
            delta["comparable_rating_pairs"],
            delta["rating_pairs_within_one"] > 0, within_one_misses > 0,
        ),
    }
    score_mask = sum(1 << score for score in range(3) if delta[f"human_rating_score_{score}_count"] > 0)
    for mask in range(8):
        outcomes[f"human_rating_pattern_{mask:03b}"] = (True, False, False) if score_mask == mask else (False, False, False)
    return outcomes


def _ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def _summarize(bucket: dict[str, Any], tolerance_ms: int) -> dict[str, Any]:
    matched = bucket["matched_question_boundaries"]
    ref_total = bucket["reference_question_boundaries"]
    pred_total = bucket["prediction_question_boundaries"]
    precision = _ratio(matched, pred_total)
    recall = _ratio(matched, ref_total)
    f1 = None if precision is None or recall is None or precision + recall == 0 else round(
        2 * precision * recall / (precision + recall), 4
    )
    return {
        "samples": bucket["samples"],
        "reference_utterances": bucket["reference_utterances"],
        "matched_utterances": bucket["matched_utterances"],
        "utterance_coverage": _ratio(bucket["matched_utterances"], bucket["reference_utterances"]),
        "speaker_pair_accuracy": _ratio(bucket["speaker_pair_correct"], bucket["speaker_pairs"]),
        "speaker_pair_denominator": bucket["speaker_pairs"],
        "timing_tolerance_ms": tolerance_ms,
        "timing_within_tolerance_rate": _ratio(bucket["timing_within_tolerance"], bucket["timed_utterances"]),
        "timed_utterance_denominator": bucket["timed_utterances"],
        "question_boundary_precision": precision,
        "question_boundary_recall": recall,
        "question_boundary_f1": f1,
        "question_boundary_reference_denominator": ref_total,
        "question_boundary_prediction_denominator": pred_total,
        "question_boundary_match_count": matched,
        "question_boundary_mean_absolute_error_ms": _ratio(bucket["question_boundary_error_sum_ms"], matched),
        "duplicate_reference_boundaries_removed": bucket["duplicate_reference_boundaries_removed"],
        "duplicate_prediction_boundaries_removed": bucket["duplicate_prediction_boundaries_removed"],
        "citation_truth_rate": _ratio(bucket["truthful_citations"], bucket["citations"]),
        "citation_denominator": bucket["citations"],
        "human_advice_score_mean_0_to_2": _ratio(bucket["human_score_sum"], bucket["human_ratings"]),
        "human_rating_denominator": bucket["human_ratings"],
        "human_exact_agreement_rate": _ratio(bucket["exact_rating_pairs"], bucket["comparable_rating_pairs"]),
        "human_within_one_agreement_rate": _ratio(bucket["rating_pairs_within_one"], bucket["comparable_rating_pairs"]),
        "human_double_scored_recommendation_denominator": bucket["comparable_rating_pairs"],
    }


def evaluate(records: list[Any], tolerance_ms: int = 500, now: datetime | None = None) -> dict[str, Any]:
    """Build an exact, content-free audit. Keep this object in restricted storage."""
    if tolerance_ms < 0:
        raise ValueError("tolerance_ms must be nonnegative")

    sample_id_counts = Counter(
        record.get("sample_id") for record in records
        if isinstance(record, dict)
        and isinstance(record.get("sample_id"), str)
        and PSEUDONYM.fullmatch(record["sample_id"])
    )
    duplicate_ids = {sample_id for sample_id, count in sample_id_counts.items() if count > 1}
    duplicate_records = sum(sample_id_counts[sample_id] for sample_id in duplicate_ids)

    total = _new_bucket()
    strata_buckets: dict[str, dict[str, Any]] = defaultdict(_new_bucket)
    stratum_cell_buckets: dict[str, dict[str, Any]] = defaultdict(_new_bucket)
    sample_contribution_total = _new_sample_contribution_bucket()
    sample_contribution_strata: dict[str, dict[str, dict[str, int]]] = defaultdict(_new_sample_contribution_bucket)
    sample_contribution_cells: dict[str, dict[str, dict[str, int]]] = defaultdict(_new_sample_contribution_bucket)
    invalid_records = 0
    for record in records:
        sample_id = record.get("sample_id") if isinstance(record, dict) else None
        if sample_id in duplicate_ids:
            continue  # Exclude every row for that ID; no first-row/order advantage.
        try:
            data, strata = validate_record(record, now=now)
        except RecordError:
            invalid_records += 1
            continue
        outcomes = _add_record(total, data, tolerance_ms)
        _add_sample_contributions(sample_contribution_total, outcomes)
        for stratum in strata:
            stratum_outcomes = _add_record(strata_buckets[stratum], data, tolerance_ms)
            _add_sample_contributions(sample_contribution_strata[stratum], stratum_outcomes)
        cell_key = "|".join(sorted(strata))
        cell_outcomes = _add_record(stratum_cell_buckets[cell_key], data, tolerance_ms)
        _add_sample_contributions(sample_contribution_cells[cell_key], cell_outcomes)

    input_count = len(records)
    eligible = total["samples"]
    excluded = duplicate_records + invalid_records
    if input_count != eligible + excluded:
        raise AssertionError("record-flow counts are inconsistent")
    return {
        "schema": "pitayaflow.mr3.restricted-audit.v4",
        "tolerance_ms": tolerance_ms,
        "input_record_count": input_count,
        "eligible_sample_count": eligible,
        "excluded_record_count": excluded,
        "duplicate_sample_id_count": len(duplicate_ids),
        "duplicate_record_count": duplicate_records,
        "invalid_record_count": invalid_records,
        "count_invariant": "input=eligible+excluded; excluded=duplicate_records+invalid_records",
        "aggregate": _summarize(total, tolerance_ms),
        "strata": {name: _summarize(bucket, tolerance_ms) for name, bucket in sorted(strata_buckets.items())},
        "stratum_cells": {
            name: _summarize(bucket, tolerance_ms) for name, bucket in sorted(stratum_cell_buckets.items())
        },
        # Restricted-only primitive counters let the release gate inspect each
        # ratio numerator, complement, score band and exact stratum cell before
        # rounded public metrics are constructed.
        "disclosure_counts": {
            "aggregate": dict(total),
            "strata": {name: dict(bucket) for name, bucket in sorted(strata_buckets.items())},
            "stratum_cells": {name: dict(bucket) for name, bucket in sorted(stratum_cell_buckets.items())},
        },
        "sample_contributions": {
            "aggregate": sample_contribution_total,
            "strata": {name: bucket for name, bucket in sorted(sample_contribution_strata.items())},
            "stratum_cells": {name: bucket for name, bucket in sorted(sample_contribution_cells.items())},
        },
        "quality_thresholds": None,
        "note": "Restricted local audit only; descriptive metrics; no provider call or pass/fail decision.",
    }


def _is_small_count(value: Any, threshold: int) -> bool:
    return type(value) is int and 0 < value < threshold


def _has_small_complement(bucket: dict[str, Any], total_key: str, part_key: str, threshold: int) -> bool:
    total, part = bucket.get(total_key), bucket.get(part_key)
    if type(total) is not int or type(part) is not int or part < 0 or part > total:
        return True
    return _is_small_count(part, threshold) or _is_small_count(total - part, threshold)


def _bucket_has_small_cell(bucket: Any, threshold: int) -> bool:
    if not isinstance(bucket, dict) or not bucket:
        return True
    # All primitive bucket values are nonnegative integer counts or integer
    # sums. This deliberately errs on suppression if a new metric is added
    # without an explicit disclosure review.
    if any(type(value) is not int or value < 0 for value in bucket.values()):
        return True
    if any(_is_small_count(value, threshold) for value in bucket.values()):
        return True

    partitions = (
        ("reference_utterances", "matched_utterances"),
        ("speaker_pairs", "speaker_pair_correct"),
        ("timed_utterances", "timing_within_tolerance"),
        ("reference_question_boundaries", "matched_question_boundaries"),
        ("prediction_question_boundaries", "matched_question_boundaries"),
        ("citations", "truthful_citations"),
        ("comparable_rating_pairs", "exact_rating_pairs"),
        ("comparable_rating_pairs", "rating_pairs_within_one"),
    )
    for total_key, part_key in partitions:
        if total_key not in bucket or part_key not in bucket:
            return True
        if _has_small_complement(bucket, total_key, part_key, threshold):
            return True

    ratings_total = bucket.get("human_ratings")
    rating_bins = [bucket.get(f"human_rating_score_{score}_count") for score in range(3)]
    if type(ratings_total) is not int or any(type(value) is not int for value in rating_bins):
        return True
    if sum(rating_bins) != ratings_total:
        return True
    if any(_is_small_count(value, threshold) for value in rating_bins):
        return True
    if any(_is_small_count(ratings_total - value, threshold) for value in rating_bins):
        return True

    comparable = bucket.get("comparable_rating_pairs")
    exact = bucket.get("exact_rating_pairs")
    within = bucket.get("rating_pairs_within_one")
    if type(comparable) is not int or type(exact) is not int or type(within) is not int:
        return True
    if not (0 <= exact <= within <= comparable):
        return True
    agreement_cells = (exact, within - exact, comparable - within)
    if any(_is_small_count(value, threshold) for value in agreement_cells):
        return True
    return False


def _contribution_bucket_has_small_cell(bucket: Any, sample_count: int, threshold: int) -> bool:
    if not isinstance(bucket, dict) or set(bucket) != set(SAMPLE_CONTRIBUTION_METRICS):
        return True
    if type(sample_count) is not int or sample_count < 0:
        return True
    for metric_counts in bucket.values():
        if not isinstance(metric_counts, dict) or set(metric_counts) != {
            "contributors", "success_samples", "failure_samples", "both_samples"
        }:
            return True
        contributors = metric_counts["contributors"]
        success = metric_counts["success_samples"]
        failure = metric_counts["failure_samples"]
        both = metric_counts["both_samples"]
        if any(type(value) is not int or value < 0 or value > sample_count for value in (contributors, success, failure, both)):
            return True
        if success > contributors or failure > contributors or both > min(success, failure):
            return True
        missing = sample_count - contributors
        success_only = success - both
        failure_only = failure - both
        contributor_without_outcome = contributors - success_only - failure_only - both
        cells = (contributors, missing, success_only, failure_only, both, contributor_without_outcome)
        if contributor_without_outcome < 0 or sum((missing, success_only, failure_only, both, contributor_without_outcome)) != sample_count:
            return True
        if any(_is_small_count(value, threshold) for value in cells):
            return True
    return False


def _sample_contribution_is_consistent(counts: Any, raw: Any, sample_count: int) -> bool:
    """Check sample contributor counters against the restricted event totals."""
    if not isinstance(counts, dict) or not isinstance(raw, dict):
        return False
    denominator_for = {
        "utterance_coverage": ("reference_utterances",),
        "speaker_pair_accuracy": ("speaker_pairs",),
        "timing_within_tolerance_rate": ("timed_utterances",),
        "question_boundary_reference": ("reference_question_boundaries", "duplicate_reference_boundaries_removed"),
        "question_boundary_prediction": ("prediction_question_boundaries", "duplicate_prediction_boundaries_removed"),
        "question_boundary_f1": ("reference_question_boundaries", "prediction_question_boundaries"),
        "question_boundary_mean_absolute_error_ms": ("matched_question_boundaries",),
        "duplicate_reference_boundaries_removed": ("reference_question_boundaries", "duplicate_reference_boundaries_removed"),
        "duplicate_prediction_boundaries_removed": ("prediction_question_boundaries", "duplicate_prediction_boundaries_removed"),
        "citation_truth_rate": ("citations",),
        "recommendation_item_count": ("recommendation_items",),
        "human_advice_score_mean_0_to_2": ("human_ratings",),
        "human_rating_score_0_count": ("human_rating_score_0_count",),
        "human_rating_score_1_count": ("human_rating_score_1_count",),
        "human_rating_score_2_count": ("human_rating_score_2_count",),
        "human_exact_agreement_rate": ("comparable_rating_pairs",),
        "human_within_one_agreement_rate": ("comparable_rating_pairs",),
    }
    success_for = {
        "utterance_coverage": lambda bucket: bucket["matched_utterances"],
        "speaker_pair_accuracy": lambda bucket: bucket["speaker_pair_correct"],
        "timing_within_tolerance_rate": lambda bucket: bucket["timing_within_tolerance"],
        "question_boundary_reference": lambda bucket: bucket["matched_question_boundaries"],
        "question_boundary_prediction": lambda bucket: bucket["matched_question_boundaries"],
        "question_boundary_f1": lambda bucket: bucket["matched_question_boundaries"],
        "question_boundary_mean_absolute_error_ms": lambda bucket: bucket["matched_question_boundaries"],
        "citation_truth_rate": lambda bucket: bucket["truthful_citations"],
        "human_exact_agreement_rate": lambda bucket: bucket["exact_rating_pairs"],
        "human_within_one_agreement_rate": lambda bucket: bucket["rating_pairs_within_one"],
    }
    failure_for = {
        "utterance_coverage": lambda bucket: bucket["reference_utterances"] - bucket["matched_utterances"],
        "speaker_pair_accuracy": lambda bucket: bucket["speaker_pairs"] - bucket["speaker_pair_correct"],
        "timing_within_tolerance_rate": lambda bucket: bucket["timed_utterances"] - bucket["timing_within_tolerance"],
        "question_boundary_reference": lambda bucket: bucket["reference_question_boundaries"] - bucket["matched_question_boundaries"],
        "question_boundary_prediction": lambda bucket: bucket["prediction_question_boundaries"] - bucket["matched_question_boundaries"],
        "question_boundary_f1": lambda bucket: (
            bucket["reference_question_boundaries"] - bucket["matched_question_boundaries"]
            + bucket["prediction_question_boundaries"] - bucket["matched_question_boundaries"]
        ),
        "question_boundary_mean_absolute_error_ms": lambda bucket: bucket["question_boundary_error_sum_ms"],
        "duplicate_reference_boundaries_removed": lambda bucket: bucket["duplicate_reference_boundaries_removed"],
        "duplicate_prediction_boundaries_removed": lambda bucket: bucket["duplicate_prediction_boundaries_removed"],
        "citation_truth_rate": lambda bucket: bucket["citations"] - bucket["truthful_citations"],
        "human_exact_agreement_rate": lambda bucket: bucket["comparable_rating_pairs"] - bucket["exact_rating_pairs"],
        "human_within_one_agreement_rate": lambda bucket: bucket["comparable_rating_pairs"] - bucket["rating_pairs_within_one"],
    }
    for metric in SAMPLE_CONTRIBUTION_METRICS:
        metric_counts = counts.get(metric)
        if not isinstance(metric_counts, dict):
            return False
        contributors = metric_counts.get("contributors")
        denominator_keys = denominator_for.get(metric)
        if denominator_keys is None:
            # Score-pattern buckets are mutually exclusive sample categories.
            if type(contributors) is not int or contributors < 0 or contributors > sample_count:
                return False
            continue
        denominator = [raw.get(key) for key in denominator_keys]
        if any(type(value) is not int or value < 0 for value in denominator):
            return False
        total = min(denominator) if metric == "question_boundary_f1" else sum(denominator)
        if type(contributors) is not int or contributors < 0 or contributors > min(sample_count, total):
            return False
        if (total == 0) != (contributors == 0):
            return False
        success_count = success_for.get(metric, lambda _: 0)(raw)
        failure_count = failure_for.get(metric, lambda _: 0)(raw)
        if type(success_count) is not int or type(failure_count) is not int:
            return False
        success_samples = metric_counts.get("success_samples")
        if metric in ("question_boundary_mean_absolute_error_ms",):
            if raw["matched_question_boundaries"] == 0 and success_samples != 0:
                return False
            if raw["matched_question_boundaries"] > 0 and raw["question_boundary_error_sum_ms"] == 0 and success_samples == 0:
                return False
        elif metric in ("duplicate_reference_boundaries_removed", "duplicate_prediction_boundaries_removed"):
            raw_total = sum(raw[key] for key in denominator_for[metric])
            duplicate_key = "duplicate_reference_boundaries_removed" if "reference" in metric else "duplicate_prediction_boundaries_removed"
            if raw_total == 0 and success_samples != 0:
                return False
            if raw_total > 0 and raw[duplicate_key] == 0 and success_samples == 0:
                return False
        elif (success_count == 0) != (success_samples == 0):
            return False
        if (failure_count == 0) != (metric_counts.get("failure_samples") == 0):
            return False
    patterns = [counts[f"human_rating_pattern_{mask:03b}"]["contributors"] for mask in range(8)]
    if sum(patterns) != sample_count:
        return False
    for score in range(3):
        band_samples = sum(patterns[mask] for mask in range(8) if mask & (1 << score))
        if band_samples != counts[f"human_rating_score_{score}_count"]["contributors"]:
            return False
    rated_samples = sum(patterns[mask] for mask in range(1, 8))
    return rated_samples == counts["human_advice_score_mean_0_to_2"]["contributors"]


def _flow_has_small_cell(audit: dict[str, Any], threshold: int) -> bool:
    flow_fields = (
        "input_record_count", "eligible_sample_count", "excluded_record_count",
        "duplicate_sample_id_count", "duplicate_record_count", "invalid_record_count",
    )
    values = {key: audit.get(key) for key in flow_fields}
    if any(type(value) is not int or value < 0 for value in values.values()):
        return True
    if any(_is_small_count(value, threshold) for value in values.values()):
        return True

    input_count = values["input_record_count"]
    eligible = values["eligible_sample_count"]
    excluded = values["excluded_record_count"]
    duplicate_ids = values["duplicate_sample_id_count"]
    duplicate_records = values["duplicate_record_count"]
    invalid = values["invalid_record_count"]
    if input_count != eligible + excluded or excluded != duplicate_records + invalid:
        return True
    unique_id_rows = input_count - duplicate_records
    derived_cells = (
        duplicate_records - duplicate_ids,
        duplicate_records - 2 * duplicate_ids,
        unique_id_rows,
        unique_id_rows - invalid,
        eligible + invalid,
    )
    return any(value < 0 or _is_small_count(value, threshold) for value in derived_cells)


def release_summary(audit: dict[str, Any]) -> dict[str, Any]:
    """Create a fail-closed export from restricted primitive counters."""
    threshold = MINIMUM_RELEASE_GROUP_SIZE
    suppressed = {
        "schema": "pitayaflow.mr3.public-summary.v2",
        "aggregate": {"status": "suppressed"},
        "strata": {},
        "record_flow": {"status": "suppressed"},
        "release_policy": "suppressed_pending_disclosure_review",
    }
    if not isinstance(audit, dict):
        return suppressed
    if audit.get("schema") != "pitayaflow.mr3.restricted-audit.v4":
        return suppressed
    tolerance_ms = audit.get("tolerance_ms")
    if type(tolerance_ms) is not int or tolerance_ms < 0:
        return suppressed

    disclosures = audit.get("disclosure_counts")
    if not isinstance(disclosures, dict):
        return suppressed
    aggregate_counts = disclosures.get("aggregate")
    strata_counts = disclosures.get("strata")
    cell_counts = disclosures.get("stratum_cells")
    strata = audit.get("strata")
    cells = audit.get("stratum_cells")
    if not isinstance(strata_counts, dict) or not isinstance(cell_counts, dict):
        return suppressed
    if not isinstance(strata, dict) or not isinstance(cells, dict):
        return suppressed
    if set(strata_counts) != set(strata) or set(cell_counts) != set(cells):
        return suppressed

    contributions = audit.get("sample_contributions")
    if not isinstance(contributions, dict):
        return suppressed
    aggregate_contributions = contributions.get("aggregate")
    strata_contributions = contributions.get("strata")
    cell_contributions = contributions.get("stratum_cells")
    if not isinstance(strata_contributions, dict) or not isinstance(cell_contributions, dict):
        return suppressed
    if set(strata_contributions) != set(strata_counts) or set(cell_contributions) != set(cell_counts):
        return suppressed
    if not isinstance(aggregate_counts, dict):
        return suppressed
    eligible = audit.get("eligible_sample_count")
    if type(eligible) is not int or eligible < threshold or aggregate_counts.get("samples") != eligible:
        return suppressed
    contribution_buckets = (
        (aggregate_contributions, aggregate_counts),
        *((strata_contributions[name], strata_counts[name]) for name in strata_counts),
        *((cell_contributions[name], cell_counts[name]) for name in cell_counts),
    )
    for contribution_bucket, raw_bucket in contribution_buckets:
        if not isinstance(raw_bucket, dict):
            return suppressed
        sample_count = raw_bucket.get("samples")
        if (
            type(sample_count) is not int
            or _contribution_bucket_has_small_cell(contribution_bucket, sample_count, threshold)
            or not _sample_contribution_is_consistent(contribution_bucket, raw_bucket, sample_count)
        ):
            return suppressed
    if audit.get("aggregate") != _summarize(aggregate_counts, tolerance_ms):
        return suppressed
    if strata != {name: _summarize(bucket, tolerance_ms) for name, bucket in sorted(strata_counts.items())}:
        return suppressed
    if cells != {name: _summarize(bucket, tolerance_ms) for name, bucket in sorted(cell_counts.items())}:
        return suppressed

    buckets = [aggregate_counts, *strata_counts.values(), *cell_counts.values()]
    if any(_bucket_has_small_cell(bucket, threshold) for bucket in buckets):
        return suppressed
    if _flow_has_small_cell(audit, threshold):
        return suppressed

    public_aggregate = _summarize(aggregate_counts, tolerance_ms)
    public_strata = {
        name: _summarize(bucket, tolerance_ms) for name, bucket in sorted(strata_counts.items())
    }

    flow_fields = (
        "input_record_count", "eligible_sample_count", "excluded_record_count",
        "duplicate_sample_id_count", "duplicate_record_count", "invalid_record_count",
    )
    flow_counts = {key: audit[key] for key in flow_fields}
    return {
        "schema": "pitayaflow.mr3.public-summary.v2",
        "minimum_release_group_size": threshold,
        "aggregate": {"status": "released", **public_aggregate},
        "strata": public_strata,
        "record_flow": {"status": "released", **flow_counts},
        "release_policy": f"minimum_group_size_{threshold}; complementary_suppression",
        "note": "Descriptive metrics only; freeze the evaluation version and complete a separate disclosure review before release.",
    }


def _sample(sample_id: str, strata: list[str] | None = None) -> dict[str, Any]:
    return {
        "sample_id": sample_id,
        "consent": {"authorized": True, "scope": "offline-evaluation", "authorization_ref": "consent-registry:synthetic",
                    "expires_at": "2027-01-01T00:00:00Z"},
        "strata": strata or ["multi-speaker", "noise"],
        "reference": {"utterances": [
            {"id": "r1", "speaker": "spk_a", "start_ms": 0, "end_ms": 1000, "text": "I led the migration", "question_id": "q1"},
            {"id": "r2", "speaker": "spk_b", "start_ms": 1100, "end_ms": 1800, "text": "How did you validate it?", "question_id": "q2"},
        ], "question_boundaries_ms": [1100]},
        "prediction": {"utterances": [
            {"id": "p1", "reference_id": "r1", "speaker": "speaker_1", "start_ms": 100, "end_ms": 1050, "text": "I led the migration"},
            {"id": "p2", "reference_id": "r2", "speaker": "speaker_2", "start_ms": 1100, "end_ms": 1790, "text": "How did you validate it?"},
        ], "question_boundaries_ms": [1200],
        "citations": [{"quote": "I led the migration", "reference_utterance_id": "r1", "speaker": "spk_a", "start_ms": 0, "end_ms": 1000}],
        "recommendations": [{"id": "rec1", "text": "Explain the validation evidence."}]},
        "human_reviews": [
            {"reviewer_id": "reviewer_a", "ratings": {"rec1": 2}},
            {"reviewer_id": "reviewer_b", "ratings": {"rec1": 1}},
        ],
    }


def _self_test() -> int:
    now = datetime(2026, 11, 5, tzinfo=timezone.utc)
    assert _boundary_match_summary([5, 10], [0, 6], 5) == (2, 9)
    assert _boundary_match_summary([1, 3, 5], [2, 4], 1) == (2, 2)
    assert _boundary_match_summary([1], [0, 2], 1) == (1, 1)
    assert _boundary_match_summary([3, 1], [2, 0], 1) == _boundary_match_summary([1, 3], [0, 2], 1)
    assert _boundary_match_summary([1], [2], 0) == (0, 0)
    assert _boundary_match_summary([], [], 0) == (0, 0)
    assert _boundaries([10, 5, 5], "test") == ([5, 10], 1)

    good = _sample("eval_a01")
    forged = json.loads(json.dumps(good))
    forged["sample_id"] = "eval_a02"
    forged["prediction"]["citations"][0]["quote"] = "I never said this"
    forged["prediction"]["citations"][0]["speaker"] = "spk_forged"
    same_content_new_id = json.loads(json.dumps(good))
    same_content_new_id["sample_id"] = "eval_a03"
    duplicate_one = _sample("eval_dup1")
    duplicate_one["sample_id"] = "eval_dup1"
    duplicate_two = json.loads(json.dumps(duplicate_one))
    rejected = _sample("eval_a04")
    rejected["consent"]["authorized"] = False
    expired = _sample("eval_a05")
    expired["consent"]["expires_at"] = "2026-11-04T23:59:59Z"

    audit = evaluate([good, forged, same_content_new_id, duplicate_one, duplicate_two, rejected, expired], now=now)
    assert audit["input_record_count"] == 7
    assert audit["eligible_sample_count"] == 3
    assert audit["excluded_record_count"] == 4
    assert audit["duplicate_sample_id_count"] == 1
    assert audit["duplicate_record_count"] == 2
    assert audit["invalid_record_count"] == 2
    assert audit["input_record_count"] == audit["eligible_sample_count"] + audit["excluded_record_count"]
    assert audit["aggregate"]["question_boundary_f1"] == 1.0
    assert audit["aggregate"]["citation_truth_rate"] == round(2 / 3, 4)
    assert audit["aggregate"]["human_advice_score_mean_0_to_2"] == 1.5
    release = release_summary(audit)
    assert release["aggregate"]["status"] == "suppressed"
    assert release["strata"] == {}
    assert release["record_flow"]["status"] == "suppressed"
    assert set(release) == {"schema", "aggregate", "strata", "record_flow", "release_policy"}
    encoded = json.dumps({"audit": audit, "release": release})
    assert "I led the migration" not in encoded and "eval_dup1" not in encoded

    one_hit_samples = [_sample(f"eval_hit{index}") for index in range(5)]
    for sample in one_hit_samples[1:]:
        sample["prediction"]["citations"][0]["quote"] = "forged"
    one_hit_audit = evaluate(one_hit_samples, now=now)
    assert one_hit_audit["disclosure_counts"]["aggregate"]["citations"] == 5
    assert one_hit_audit["disclosure_counts"]["aggregate"]["truthful_citations"] == 1
    assert release_summary(one_hit_audit) == release

    one_owner_samples = [_sample(f"eval_owner{index}") for index in range(5)]
    one_owner_samples[0]["prediction"]["citations"] *= 5
    for sample in one_owner_samples[1:]:
        sample["prediction"]["citations"] = []
    one_owner_audit = evaluate(one_owner_samples, now=now)
    assert one_owner_audit["disclosure_counts"]["aggregate"]["citations"] == 5
    assert one_owner_audit["sample_contributions"]["aggregate"]["citation_truth_rate"]["contributors"] == 1
    assert release_summary(one_owner_audit) == release

    five_contributors_audit = evaluate([_sample(f"eval_many{index}") for index in range(5)], now=now)
    assert five_contributors_audit["sample_contributions"]["aggregate"]["citation_truth_rate"]["contributors"] == 5
    assert release_summary(five_contributors_audit)["aggregate"]["status"] == "released"

    anomalous_samples = [_sample(f"eval_anomaly{index}") for index in range(10)]
    for sample in anomalous_samples:
        sample["prediction"]["citations"] *= 5
    for citation in anomalous_samples[0]["prediction"]["citations"]:
        citation["quote"] = "forged"
    anomalous_audit = evaluate(anomalous_samples, now=now)
    assert anomalous_audit["sample_contributions"]["aggregate"]["citation_truth_rate"]["failure_samples"] == 1
    assert release_summary(anomalous_audit) == release

    missing_samples = [_sample(f"eval_missing{index}") for index in range(10)]
    for sample in missing_samples:
        sample["prediction"]["citations"] *= 5
    missing_samples[0]["prediction"]["citations"] = []
    missing_audit = evaluate(missing_samples, now=now)
    assert missing_audit["sample_contributions"]["aggregate"]["citation_truth_rate"]["contributors"] == 9
    assert release_summary(missing_audit) == release

    safe_large_audit = evaluate([_sample(f"eval_safe{index}") for index in range(10)], now=now)
    safe_large_release = release_summary(safe_large_audit)
    assert safe_large_release["aggregate"]["status"] == "released"
    assert "disclosure_counts" not in safe_large_release

    duplicate_boundary_sample = _sample("eval_boundary")
    duplicate_boundary_sample["reference"]["question_boundaries_ms"] = [1100, 1100]
    duplicate_boundary_sample["prediction"]["question_boundaries_ms"] = [1200, 1200]
    boundary_audit = evaluate([duplicate_boundary_sample], now=now)
    boundary_metrics = boundary_audit["aggregate"]
    assert boundary_metrics["question_boundary_reference_denominator"] == 1
    assert boundary_metrics["question_boundary_prediction_denominator"] == 1
    assert boundary_metrics["question_boundary_match_count"] == 1
    assert boundary_metrics["duplicate_reference_boundaries_removed"] == 1
    assert boundary_metrics["duplicate_prediction_boundaries_removed"] == 1
    print(json.dumps({
        "self_test": "passed", "synthetic_input": 7, "eligible": 3, "excluded": 4,
        "duplicate_ids": 1, "duplicate_rows": 2, "release": "small_cells_suppressed",
        "metrics": ["maximum_boundary_matching", "boundary_deduplication", "sample_id_deduplication",
                    "citation_truth", "human_advice", "distinct_sample_contributions", "score_patterns",
                    "raw_count_complements", "privacy_suppression"],
    }, separators=(",", ":")))
    return 0


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="restricted local JSONL; contents are never printed")
    parser.add_argument("--output", type=Path, help="small-cell-safe aggregate release; defaults to stdout")
    parser.add_argument("--audit-output", type=Path, help="optional exact counts/metrics file; MUST remain in restricted local storage")
    parser.add_argument("--tolerance-ms", type=int, default=500)
    parser.add_argument("--self-test", action="store_true", help="run deterministic synthetic-only cases")
    args = parser.parse_args(argv)
    if args.self_test:
        if args.input or args.output or args.audit_output:
            parser.error("--self-test cannot be combined with file paths")
        return _self_test()
    if not args.input:
        parser.error("--input is required unless --self-test is used")
    if args.output and args.audit_output and args.output.resolve() == args.audit_output.resolve():
        parser.error("release and restricted audit paths must be different")

    records: list[Any] = []
    try:
        with args.input.open("r", encoding="utf-8") as stream:
            for line_number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    records.append(json.loads(line))
                except json.JSONDecodeError:
                    print(f"invalid JSON on input line {line_number}; content withheld", file=sys.stderr)
                    return 2
        audit = evaluate(records, tolerance_ms=args.tolerance_ms)
        release = release_summary(audit)
        if args.audit_output:
            _write_json(args.audit_output, audit)
        encoded = json.dumps(release, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8")
        else:
            sys.stdout.write(encoded)
    except (OSError, ValueError) as exc:
        print(f"evaluation failed: {type(exc).__name__}; content withheld", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
