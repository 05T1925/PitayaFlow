import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { attemptFailure, createAttemptCleanup } from './cleanup.mjs'

const home = mkdtempSync(resolve(tmpdir(), 'pitayaflow-cleanup-inject-'))
writeFileSync(resolve(home, 'diagnostic.txt'), 'synthetic diagnostic')
let closes = 0
let removals = 0
const cleanup = createAttemptCleanup({ home,
  async close() { if (++closes === 1) throw new Error('synthetic close refusal') },
  removeHome(path) { removals++; rmSync(path, { recursive: true, force: true }) },
})
try {
  const primary = new Error('synthetic primary failure')
  const first = await cleanup()
  assert.equal(first.exit_confirmed, false)
  assert.equal(first.home_removed, false)
  assert.match(first.error.message, /close refusal/)
  const failure = attemptFailure(primary, first)
  assert.equal(failure.primary_error.message, 'synthetic primary failure')
  assert.match(failure.cleanup.error.message, /close refusal/)
  assert.equal(existsSync(resolve(home, 'diagnostic.txt')), true)
  assert.equal(removals, 0)
  const retry = await cleanup()
  assert.deepEqual(retry, { exit_confirmed: true, home_removed: true })
  assert.equal(existsSync(home), false)
  assert.deepEqual(await cleanup(), retry)
  assert.equal(closes, 2)
  assert.equal(removals, 1)
  const secondHome = mkdtempSync(resolve(tmpdir(), 'pitayaflow-remove-inject-'))
  try {
    let deleteAttempts = 0
    const deleteCleanup = createAttemptCleanup({ home: secondHome, async close() {},
      removeHome(path) {
        if (++deleteAttempts === 1) throw new Error('synthetic directory deletion refusal')
        rmSync(path, { recursive: true, force: true })
      },
    })
    const failedDelete = await deleteCleanup()
    assert.equal(failedDelete.exit_confirmed, true)
    assert.equal(failedDelete.home_removed, false)
    assert.equal(existsSync(secondHome), true)
    assert.match(failedDelete.error.message, /deletion refusal/)
    assert.equal((await deleteCleanup()).home_removed, true)
    assert.equal(existsSync(secondHome), false)
  } finally {
    if (existsSync(secondHome)) rmSync(secondHome, { recursive: true, force: true })
  }
  process.stdout.write('cleanup injection: close refusal retained home; retry and repeated close safe\n')
} finally {
  if (existsSync(home)) rmSync(home, { recursive: true, force: true })
}
