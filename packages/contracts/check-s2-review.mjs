import { readFileSync } from 'node:fs'
import { validReviewDraft } from './s2-review-protocol.mjs'
const cases=JSON.parse(readFileSync(new URL('./s2-review-cases.json',import.meta.url),'utf8')).cases
for(const test of cases)if(validReviewDraft(test.draft)!==test.structurally_valid)throw new Error(`S2 contract ${test.name}`)
process.stdout.write(`S2 review shared structures: ${cases.length} cases passed\n`)
