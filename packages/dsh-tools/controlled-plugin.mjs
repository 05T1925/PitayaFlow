import { resolve } from 'node:path'
import { readFileSync, writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

const root = process.env.PF_DSH_SOURCE
if (!root) throw new Error('PF_DSH_SOURCE is required')
const publicEntry = path => {
  const directory = resolve(root, path)
  const manifest = JSON.parse(readFileSync(resolve(directory, 'package.json'), 'utf8'))
  return pathToFileURL(resolve(directory, manifest.main)).href
}
const { defineTool } = await import(publicEntry('packages/core/tools'))
const { LlmAdapter, ToolCallId, ReasoningEffortId } = await import(publicEntry('packages/llm/llm'))
const fixtureName = process.env.PF_FIXTURE_ID === 'b' ? 'synthetic-case-b.json' : 'synthetic-case.json'
const fixture = process.env.PF_BRIDGE_URL ? null : JSON.parse(readFileSync(new URL(`../contracts/${fixtureName}`, import.meta.url), 'utf8'))
const review = process.env.PF_ATTEMPT_MODE === 'review'
const ownerId = process.env.PF_OWNER_ID ?? 'synthetic-owner-a'
const maxModelCalls = Number(process.env.PF_MAX_MODEL_CALLS ?? 8)
const maxToolCalls = Number(process.env.PF_MAX_TOOL_CALLS ?? 8)
let modelCalls = 0
let toolCalls = 0

export const name = 'pf-controlled-synthetic'
export const inject = ['tools', 'llm']

class ScriptedAdapter extends LlmAdapter {
  async resolveModel(provider, model) {
    return { provider, id: model, name: model, reasoning: { efforts: [{ id: ReasoningEffortId('off'), name: 'Off' }], defaultEffort: ReasoningEffortId('off') } }
  }
  async * stream(options) {
    const names = options.tools?.map(item => item.name) ?? []
    if (process.env.PF_AUDIT_FILE) writeFileSync(process.env.PF_AUDIT_FILE, JSON.stringify({ names,
      inherited_secret_marker: Boolean(process.env.PF_SECRET_MARKER), inherited_provider_key: Boolean(process.env.DEEPSEEK_API_KEY),
      credential_present: Boolean(process.env.PF_CREDENTIAL_MARKER) }))
    process.stderr.write(`PF_TOOL_REGISTRY=${JSON.stringify(names)}\n`)
    if (names.length !== 1 || names[0] !== (review ? 'read_review_transcript' : 'read_synthetic_transcript')) throw new Error(`unexpected tools: ${names.join(',')}`)
    if (process.env.PF_SCENARIO === 'crash') process.exit(23)
    if (process.env.PF_SCENARIO === 'blocked') await new Promise(() => {})
    const last = options.messages.at(-1)
    if (last?.role !== 'tool') {
      const forbidden = process.env.PF_SCENARIO === 'forbidden'
      const toolName = forbidden ? 'pwsh' : review ? 'read_review_transcript' : 'read_synthetic_transcript'
      const args = JSON.stringify({ case_id: process.env.PF_TARGET_CASE_ID ?? process.env.PF_BRIDGE_CASE_ID ?? fixture.case_id,
        owner_id: process.env.PF_TARGET_OWNER_ID ?? ownerId,
        authorization_revision: process.env.PF_AUTH_REV ?? 'auth-1' })
      yield { type: 'block-start', index: 0, blockType: 'tool-call' }
      yield { type: 'tool-call-delta', index: 0, id: ToolCallId('pf-call-1'), name: toolName, argumentsDelta: args }
      yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: ToolCallId('pf-call-1'), name: toolName, arguments: args } }
      yield { type: 'finish', reason: { kind: 'tool-calls' } }
      return
    }
    if (last.isError) {
      const answer = JSON.stringify({ refused: 'tool_not_allowed' })
      yield { type: 'block-start', index: 0, blockType: 'text' }
      yield { type: 'text-delta', index: 0, text: answer }
      yield { type: 'block-end', index: 0, block: { type: 'text', text: answer } }
      yield { type: 'finish', reason: { kind: 'stop' } }
      return
    }
    const observed = JSON.parse(last.content.filter(block => block.type === 'text').map(block => block.text).join(''))
    const segment = observed.segments[0]
    const draft = review ? reviewDraft(observed) : { case_id: observed.case_id, transcript_revision: observed.transcript_revision,
      ...(process.env.PF_SCENARIO === 'malformed' ? {} : { assessment: 'The candidate mentioned a queue.' }),
      citation: { transcript_revision: observed.transcript_revision, segment_id: process.env.PF_SCENARIO === 'invalid' ? 'missing' : segment.id,
        start_ms: segment.start_ms, end_ms: segment.end_ms } }
    const answer = JSON.stringify(draft)
    yield { type: 'block-start', index: 0, blockType: 'text' }
    yield { type: 'text-delta', index: 0, text: answer }
    yield { type: 'block-end', index: 0, block: { type: 'text', text: answer } }
    yield { type: 'finish', reason: { kind: 'stop' } }
  }
}

function reviewDraft(observed) {
  const ref = segment => ({ segment_id: segment.id, start_ms: segment.start_ms, end_ms: segment.end_ms, text: segment.text })
  const segments = observed.segments
  const groupFor = new Map()
  if (observed.outline?.groups) for (const group of observed.outline.groups) {
    for (const id of group.question_ids) groupFor.set(id, { id: group.id, kind: 'question' })
    for (const id of group.answer_ids) groupFor.set(id, { id: group.id, kind: 'answer' })
  }
  const groups = [], unassigned = []
  if (observed.outline?.groups) {
    for (const group of observed.outline.groups) {
      const questions = segments.filter(s => group.question_ids.includes(s.id))
      const answers = segments.filter(s => group.answer_ids.includes(s.id))
      groups.push({ id: group.id, parentId: group.parent_id ?? null, questions, answers })
    }
    for (const s of segments) if (!groupFor.has(s.id)) unassigned.push(s)
  } else {
    let active
    for (const s of segments) {
      if (s.role === 'INTERVIEWER') {
        if (!active || active.answers.length) { active = { id: `q-${groups.length + 1}`, questions: [], answers: [] }; groups.push(active) }
        active.questions.push(s)
      } else if (s.role === 'ME' && active) active.answers.push(s)
      else unassigned.push(s)
    }
  }
  const hasUnknown = segments.some(s => s.role === 'UNKNOWN')
  const questions = groups.map((group, index) => {
    const ambiguous = hasUnknown || group.questions.some(s => s.role !== 'INTERVIEWER') || group.answers.some(s => s.role !== 'ME') ||
      group.questions.some(q => group.answers.some(a => a.start_ms < q.end_ms))
    const status = ambiguous ? 'await_confirmation' : group.answers.length ? 'reviewed' : 'insufficient_evidence'
    const background = (observed.materials ?? []).map(material => ({ material_id: material.material_id, revision: material.revision,
      sha256: material.sha256, quote: material.text.slice(0, 160) }))
    const saved = (observed.practice_answers ?? []).find(item => item.question_id === group.id)
    return { id: group.id, parent_id: group.parentId ?? (index > 0 && !groups[index - 1].answers.length ? groups[index - 1].id : null),
      question_refs: group.questions.map(ref), answer_refs: group.answers.map(ref), status,
      covered_points: status === 'reviewed' ? group.answers.map(s => `原回答提及：${s.text.slice(0, 80)}`) : [], gaps: [],
      advice: '核对原回答与题目边界后，再整理有证据支持的表达。',
      reason: ambiguous ? '角色或重叠边界待确认' : group.answers.length ? '技术知识未核查；背景材料仅作评价背景' : '录音中缺少可确认的回答',
      material_background: background,
      practice_card: { goal: '练习依据原回答清楚表达', evidence_gap: status === 'reviewed' ? '背景和技术断言仍待核查' : '录音证据或角色不足',
        next_step: '核查材料与技术事实，再写文字练习稿', to_verify: ['材料和知识断言待核查'],
        follow_up: '请说明你会如何核查本题关键事实？', reference_answer: '参考表达需以原话和已核查事实为准，不可把背景材料当作已说经历。' },
      revised_answer: saved?.text ?? group.answers.map(item => item.text).join(' ').slice(0, 1800) }
  })
  if (process.env.PF_SCENARIO === 'invalid' && questions.length) {
    const first = questions.find(item => item.answer_refs.length) ?? questions[0]
    first.answer_refs.push({ segment_id: 'forged', start_ms: 0, end_ms: 1, text: 'forged' })
  }
  if (process.env.PF_SCENARIO === 'forged-spoken' && questions.length) {
    questions[0].answer_refs.push({ segment_id: 'material-as-spoken', start_ms: 0, end_ms: 1,
      text: observed.materials?.[0]?.text ?? 'forged' })
  }
  if (process.env.PF_SCENARIO === 'malformed') return { source_id: observed.source_id, source_revision: observed.source_revision }
  return { source_id: observed.source_id, source_revision: observed.source_revision, outline_revision: observed.outline_revision,
    questions, unassigned_refs: unassigned.map(ref) }
}

export function apply(ctx) {
  ctx.on('llm/stream', (options, next) => {
    modelCalls++
    if (modelCalls > maxModelCalls) throw new Error('PF_MODEL_CALL_LIMIT')
    return next()
  })
  ctx.tools.register(defineTool({
    name: review ? 'read_review_transcript' : 'read_synthetic_transcript', description: review ? 'Read the task-bound full transcript' : 'Read one fixed synthetic transcript case',
    parameters: { case_id: { type: 'string', required: true }, owner_id: { type: 'string', required: true },
      authorization_revision: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute(args) {
      toolCalls++
      if (toolCalls > maxToolCalls) throw new Error('PF_TOOL_CALL_LIMIT')
      const grant = process.env.PF_GRANT_FILE
        ? JSON.parse(readFileSync(process.env.PF_GRANT_FILE, 'utf8'))
        : { owner_id: ownerId, case_id: fixture.case_id, authorization_revision: process.env.PF_AUTH_REV ?? 'auth-1', allowed: true }
      if (process.env.PF_BRIDGE_URL) {
        if (args.case_id !== process.env.PF_BRIDGE_CASE_ID || args.owner_id !== ownerId ||
            args.authorization_revision !== process.env.PF_AUTH_REV) throw new Error('PF_SCOPE_DENIED')
        const endpoint = `${process.env.PF_BRIDGE_URL}/internal/agent/${review ? 'review/' : ''}grants/${encodeURIComponent(process.env.PF_BRIDGE_ATTEMPT)}/source?case_id=${encodeURIComponent(args.case_id)}&owner_id=${encodeURIComponent(args.owner_id)}`
        const response = await fetch(endpoint, { headers: { Authorization: `Bearer ${process.env.PF_BRIDGE_TOKEN}` },
          signal: AbortSignal.timeout(5000), redirect: 'error' })
        if (!response.ok) throw new Error(`PF_SCOPE_DENIED_${response.status}`)
        return JSON.stringify(await response.json())
      }
      if (!grant.allowed || args.case_id !== grant.case_id || args.owner_id !== grant.owner_id ||
          args.authorization_revision !== grant.authorization_revision || args.case_id !== fixture.case_id) throw new Error('PF_SCOPE_DENIED')
      return JSON.stringify(fixture)
    },
  }))
  ctx.llm.registerAdapter(['pf-scripted'], new ScriptedAdapter())
}
