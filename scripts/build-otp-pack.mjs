import { createHash } from 'node:crypto'
import { createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { ZipArchive } from 'archiver'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const packRoot = join(repositoryRoot, 'packs', 'otp')
const modelPath = resolve(
  process.env.OTP_NEEDLE_MODEL_PATH ??
    join(repositoryRoot, '..', 'OTPNeedle', '.cache', 'reasoning-v1-eval', 'generic-local-seed17-4bit.cact'),
)
const manifest = JSON.parse(readFileSync(join(packRoot, 'manifest.json'), 'utf8'))

if (!existsSync(modelPath)) throw new Error(`OTP model not found at ${modelPath}`)
const digest = createHash('sha256').update(readFileSync(modelPath)).digest('hex')
if (digest !== manifest.model.sha256) throw new Error(`OTP model digest mismatch: ${digest}`)

const outputDirectory = join(repositoryRoot, 'artifacts')
mkdirSync(outputDirectory, { recursive: true })
const outputPath = join(outputDirectory, `${manifest.id}-${manifest.version}.nbpack`)
const output = createWriteStream(outputPath)
const archive = new ZipArchive({ zlib: { level: 9 } })
const archiveDate = new Date('1980-01-01T00:00:00.000Z')

const completed = new Promise((resolvePromise, rejectPromise) => {
  output.on('close', resolvePromise)
  archive.on('warning', rejectPromise)
  archive.on('error', rejectPromise)
})

archive.pipe(output)
for (const name of ['manifest.json', 'tools.json', 'LICENSE.txt', 'NOTICE.md']) {
  archive.file(join(packRoot, name), { name, date: archiveDate, mode: 0o644 })
}
archive.append(createReadStream(modelPath), { name: 'model.cact', date: archiveDate, mode: 0o644 })
await archive.finalize()
await completed

console.log(outputPath)
