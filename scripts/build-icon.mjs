import fs from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

// Preserve the Pawth source. The adaptive background supplies the same color.
const source = await fs.readFile(new URL('../assets/needlebub-icon.svg', import.meta.url), 'utf8')
if (!/<rect\b[^>]*id="rect-2"[^>]*fill="#F3EFE7"[^>]*\/>/.test(source)) {
  throw new Error('Expected the saved Pawth background. Review the foreground export before rebuilding.')
}
await fs.copyFile(new URL('../assets/needlebub-icon.svg', import.meta.url), new URL('../public/needlebub-icon.svg', import.meta.url))
const foreground = source.replace(/<rect\b[^>]*id="rect-2"[^>]*\/>/, '')
const artworkBounds = (await sharp(Buffer.from(foreground))
  .trim({ background: '#00000000', threshold: 4 })
  .png()
  .toBuffer({ resolveWithObject: true })).info
const side = Math.ceil(Math.max(artworkBounds.width, artworkBounds.height) * 1.08)
const left = Math.round(-artworkBounds.trimOffsetLeft + (artworkBounds.width - side) / 2)
const top = Math.round(-artworkBounds.trimOffsetTop + (artworkBounds.height - side) / 2)
const headerSvg = foreground
  .replace(/<metadata\b[\s\S]*?<\/metadata>/, '')
  .replace('viewBox="0 0 1280 1280"', `viewBox="${left} ${top} ${side} ${side}"`)
await fs.writeFile(new URL('../public/needlebub-mark.svg', import.meta.url), headerSvg)
const body = foreground.replace(/<\?xml[^>]*\?>/, '').replace(/<svg\b[^>]*>/, '').replace(/<\/svg>\s*$/, '')
const exportSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 1280"><g transform="translate(64 64) scale(.9)">${body}</g></svg>`
const directory = new URL('../android/app/src/main/res/drawable-xxxhdpi/', import.meta.url)
await fs.mkdir(directory, { recursive: true })
await sharp(Buffer.from(exportSvg)).resize(432, 432).png().toFile(fileURLToPath(new URL('ic_launcher_artwork.png', directory)))
console.log('Exported saved Pawth artwork for the adaptive launcher icon.')
