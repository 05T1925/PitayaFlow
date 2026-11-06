import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { validateProtocol } from './s1-agent-protocol.mjs'
const cases = JSON.parse(readFileSync(new URL('./s1-agent-protocol-cases.json', import.meta.url), 'utf8'))
for (const item of cases) assert.equal(Boolean(validateProtocol(item.kind, item.value)), item.valid, item.kind)
process.stdout.write(`S1 agent protocol: ${cases.length} shared cases passed\n`)
