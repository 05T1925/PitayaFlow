import { readFileSync } from 'node:fs'

const schema = JSON.parse(readFileSync(new URL('./s2-review-protocol.json', import.meta.url), 'utf8')).review_draft
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const fields = (value, required) => object(value) && Object.entries(required).every(([key, kind]) => {
  const item = value[key]
  if (kind === 'string') return typeof item === 'string' && item.trim().length > 0
  if (kind === 'text') return typeof item === 'string'
  if (kind === 'integer') return Number.isSafeInteger(item) && item >= 0
  if (kind === 'array') return Array.isArray(item)
  return object(item)
})
export function validReviewDraft(draft) {
  return fields(draft, schema.required) &&
    draft.questions.every(question => fields(question, schema.question_required) &&
      (Object.keys(schema.source_fields).every(key => question[key] === undefined) ||
        fields(question, schema.source_fields) &&
        question.material_background.every(ref => fields(ref, { material_id: 'string', revision: 'integer', sha256: 'string', quote: 'string' })) &&
        fields(question.practice_card, { goal: 'string', evidence_gap: 'string', next_step: 'string', to_verify: 'array',
          follow_up: 'string', reference_answer: 'string' })) &&
      [...question.question_refs, ...question.answer_refs].every(ref => fields(ref, schema.reference_required))) &&
    draft.unassigned_refs.every(ref => fields(ref, schema.reference_required))
}
