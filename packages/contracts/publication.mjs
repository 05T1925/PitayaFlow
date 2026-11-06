import { validateDraft } from './validate-draft.mjs'

const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const nonempty = value => typeof value === 'string' && value.trim().length > 0

/** Parse one untrusted serialized attempt specification before work begins. */
export function parseRunSpec(value) {
  if (!record(value)) return { ok: false, reason: 'invalid_spec' }
  for (const key of ['task_id', 'run_id', 'attempt_id', 'owner_id', 'question_id', 'idempotency_key',
    'transcript_revision', 'speaker_mapping_revision', 'material_revision', 'authorization_revision']) {
    if (!nonempty(value[key])) return { ok: false, reason: 'invalid_spec', field: key }
  }
  for (const key of ['execution_epoch', 'state_revision', 'deadline_ms', 'max_model_calls', 'max_tool_calls', 'output_limit_bytes']) {
    if (!Number.isSafeInteger(value[key]) || value[key] < 0) return { ok: false, reason: 'invalid_spec', field: key }
  }
  if (value.deadline_ms === 0 || value.output_limit_bytes === 0) return { ok: false, reason: 'invalid_spec' }
  return { ok: true, spec: value }
}

/** Trusted publication gate: a valid model draft is insufficient without current eligibility. */
export function publicationDecision(specValue, current, draft, fixture) {
  const parsed = parseRunSpec(specValue)
  if (!parsed.ok) return { publish: false, reason: parsed.reason }
  const spec = parsed.spec
  if (!record(current) || current.owner_id !== spec.owner_id || current.question_id !== spec.question_id)
    return { publish: false, reason: 'scope_denied' }
  if (current.deleted) return { publish: false, reason: 'deleted' }
  if (current.cancelled) return { publish: false, reason: 'cancelled' }
  if (!current.granted || current.authorization_revision !== spec.authorization_revision) return { publish: false, reason: 'authorization_revoked' }
  if (current.task_id !== spec.task_id || current.run_id !== spec.run_id || current.attempt_id !== spec.attempt_id ||
      current.execution_epoch !== spec.execution_epoch) return { publish: false, reason: 'stale_attempt' }
  if (current.state_revision !== spec.state_revision || current.transcript_revision !== spec.transcript_revision ||
      current.speaker_mapping_revision !== spec.speaker_mapping_revision || current.material_revision !== spec.material_revision) return { publish: false, reason: 'stale_input' }
  const validation = validateDraft(draft, fixture)
  if (!validation.usable) return { publish: false, reason: validation.reason }
  if (draft.transcript_revision !== current.transcript_revision) return { publish: false, reason: 'stale_input' }
  return { publish: true, reason: 'eligible' }
}

/** A summary remains usable only while every immutable source can be fetched. */
export function summaryDecision(summary, current, resolveSource) {
  if (!record(summary) || !Array.isArray(summary.sources) || summary.sources.length === 0) return { usable: false, reason: 'missing_source' }
  if (summary.attempt_id !== current.attempt_id || summary.execution_epoch !== current.execution_epoch ||
      summary.state_revision !== current.state_revision || summary.transcript_revision !== current.transcript_revision ||
      summary.speaker_mapping_revision !== current.speaker_mapping_revision || summary.material_revision !== current.material_revision) return { usable: false, reason: 'stale_summary' }
  for (const ref of summary.sources) {
    if (!record(ref) || !nonempty(ref.id) || !nonempty(ref.revision) || !nonempty(ref.content_hash) ||
        !resolveSource(ref)) return { usable: false, reason: 'missing_source' }
  }
  return { usable: true, reason: 'sources_available' }
}
