import { createHash } from 'node:crypto'
import { publicationDecision, summaryDecision } from '../../../packages/contracts/publication.mjs'

/** Test-only stand-in for the future trusted business store, never loaded by runtime. */
export class MemoryFacts {
  constructor(spec, fixture) {
    this.fixture = fixture
    this.current = { ...spec, granted: true, cancelled: false, deleted: false }
    this.published = new Map()
    this.sources = new Map()
    this.summary = null
    this.toolResults = new Map()
    this.readOnlyReplays = new Map()
    this.externalEffects = new Map()
  }

  update(changes) { this.current = { ...this.current, ...changes, state_revision: this.current.state_revision + 1 } }
  cancel() { this.update({ cancelled: true }) }
  delete() { this.update({ deleted: true }) }
  revoke() { this.update({ granted: false, authorization_revision: `revoked-${this.current.state_revision + 1}` }) }
  nextAttempt(id) { this.update({ attempt_id: id, execution_epoch: this.current.execution_epoch + 1 }) }

  publish(spec, draft) {
    const decision = publicationDecision(spec, this.current, draft, this.fixture)
    if (!decision.publish) return decision
    const key = `${spec.owner_id}:${spec.idempotency_key}`
    const digest = createHash('sha256').update(JSON.stringify({ spec, draft })).digest('hex')
    const previous = this.published.get(key)
    if (previous) return previous.digest === digest
      ? { publish: true, reason: 'duplicate', artifact_id: previous.artifact_id }
      : { publish: false, reason: 'idempotency_conflict' }
    const artifact_id = `artifact-${this.published.size + 1}`
    this.published.set(key, { digest, artifact_id })
    return { publish: true, reason: 'published', artifact_id }
  }

  addSource(ref, content) {
    const key = `${ref.id}:${ref.revision}:${ref.content_hash}`
    if (this.sources.has(key)) return this.sources.get(key) === content
      ? { stored: true, reason: 'duplicate' } : { stored: false, reason: 'source_conflict' }
    this.sources.set(key, content)
    return { stored: true, reason: 'created' }
  }
  resolveSource(ref) { return this.sources.get(`${ref.id}:${ref.revision}:${ref.content_hash}`) }
  saveSummary(summary) {
    const decision = summaryDecision(summary, this.current, ref => this.resolveSource(ref))
    if (decision.usable) this.summary = structuredClone(summary)
    return decision
  }
  readSummary() { return summaryDecision(this.summary, this.current, ref => this.resolveSource(ref)) }

  recordToolResult(questionId, callId, value) {
    const key = `${questionId}:${callId}`
    if (this.toolResults.has(key)) return { accepted: false, reason: 'duplicate_call' }
    this.toolResults.set(key, { question_id: questionId, tool_call_id: callId, value })
    return { accepted: true }
  }
  questionResults() { return [...this.toolResults.values()].sort((a, b) => a.question_id.localeCompare(b.question_id)) }

  replayReadOnly(spec, draft, key) {
    if (key !== spec.idempotency_key || key !== this.current.idempotency_key)
      return { accepted: false, reason: 'idempotency_conflict' }
    const decision = publicationDecision(spec, this.current, draft, this.fixture)
    if (!decision.publish) return { accepted: false, reason: decision.reason }
    if (this.readOnlyReplays.has(key)) return { accepted: true, reason: 'reused' }
    this.readOnlyReplays.set(key, { checked: true })
    return { accepted: true, reason: 'executed' }
  }

  noteExternalUnknown(key) { this.externalEffects.set(key, { state: 'unknown_outcome', sends: 1 }) }
  resumeExternal(key) { return this.externalEffects.get(key) ?? { state: 'not_found', sends: 0 } }
}
