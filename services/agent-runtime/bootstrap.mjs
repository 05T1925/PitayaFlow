import { spawn, spawnSync } from 'node:child_process'
import { resolve } from 'node:path'
import { runOwnedWatchdog } from './attempt-host.mjs'

const root = process.env.PF_DSH_SOURCE
if (!root) throw new Error('Set PF_DSH_SOURCE to the pinned deepseek-harness checkout')
const sha = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' })
if (sha.status !== 0 || sha.stdout.trim() !== 'ce7073d51254c26cd63b22a60835b8f948deb447') throw new Error('DSH source SHA mismatch')
const child = spawn(process.execPath, [resolve('services/agent-runtime/run.mjs'), process.argv[2] ?? 'success'], {
  cwd: process.cwd(), env: process.env, stdio: 'inherit', detached: process.platform !== 'win32', windowsHide: true,
})
const outcome = await runOwnedWatchdog(child, 90000)
if (outcome.error) process.stderr.write(`${outcome.error}\n`)
process.exitCode = outcome.watchdog_fired ? 1 : outcome.code ?? 1
