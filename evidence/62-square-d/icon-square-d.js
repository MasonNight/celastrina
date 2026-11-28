// Renders design D (evidence/62-icon/icon-d.js) composed for a square: square ic_launcher at every density,
// D's 16:9 art as the banner. Usage: node icon-square-d.js <res dir> [preview dir]
const sharp = require('sharp');
const [RES, PREVIEW] = process.argv.slice(2);
const NAVY = '#070B18';
const WING = 'M3,-4 C18,-38 58,-64 86,-58 C97,-54 94,-32 82,-15 C66,4 32,7 3,3 Z M3,5 C30,5 62,13 67,35 C70,53 51,64 34,57 C17,50 6,30 3,11 Z';
const BODY = 'M-3.4,4 a3.4,30 0 1,0 6.8,0 a3.4,30 0 1,0 -6.8,0 Z M-4.6,-28 a4.6,4.6 0 1,0 9.2,0 a4.6,4.6 0 1,0 -9.2,0 Z M-20.5,-63 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 Z M14.5,-63 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 Z';
const defs = `<defs><radialGradient id="moon" cx="0.42" cy="0.38" r="0.7">
  <stop offset="0" stop-color="#D6E8FF"/><stop offset="0.6" stop-color="#7AA7F5"/><stop offset="1" stop-color="#3F62C9"/></radialGradient></defs>`;
function moon(cx, cy, r) {
  const k = (0.63 * r) / 72;
  return `<circle cx="${cx}" cy="${cy}" r="${r}" fill="url(#moon)"/>
  <g transform="translate(${cx} ${cy + (4.5 * r) / 72}) scale(${k})" fill="${NAVY}">
    <path d="${WING}"/><path d="${WING}" transform="scale(-1 1)"/><path d="${BODY}"/>
    <path d="M-1.5,-31 Q-8,-50 -17,-62 M1.5,-31 Q8,-50 17,-62" stroke="${NAVY}" stroke-width="2" stroke-linecap="round" fill="none"/>
  </g>`;
}
const name = (x, y, size, len) => `<text x="${x}" y="${y}" text-anchor="middle" textLength="${len}" lengthAdjust="spacingAndGlyphs"
  font-family="Liberation Serif" font-style="italic" font-size="${size}" fill="#D6E8FF">Celastrina</text>`;
// Square: the name spans almost the full width, as large as the square allows; the moon fills the space below.
const square = `<svg xmlns="http://www.w3.org/2000/svg" width="180" height="180" viewBox="0 0 180 180">${defs}
  <rect width="180" height="180" fill="${NAVY}"/>${name(90, 46, 42, 156)}${moon(90, 119, 55)}</svg>`;
// Banner: D's 16:9 art unchanged.
const wide = `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180">${defs}
  <rect width="320" height="180" fill="${NAVY}"/>
  <text x="160" y="46" text-anchor="middle" font-family="Liberation Serif" font-style="italic" font-size="44" fill="#D6E8FF">Celastrina</text>
  ${moon(160, 116, 54)}</svg>`;
const draw = (svg, w, h) => sharp(Buffer.from(svg), { density: 72 * 8 }).resize(w, h, { fit: 'fill', kernel: 'lanczos3' })
  .flatten({ background: NAVY }).png({ compressionLevel: 9 }).toBuffer();
const icons = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const banners = { mdpi: 160, hdpi: 240, xhdpi: 320, xxhdpi: 480 };
(async () => {
  for (const [d, s] of Object.entries(icons)) await sharp(await draw(square, s, s)).toFile(`${RES}/mipmap-${d}/ic_launcher.png`);
  for (const [d, w] of Object.entries(banners)) await sharp(await draw(wide, w, (w * 9) / 16)).toFile(`${RES}/drawable-${d}/banner.png`);
  if (PREVIEW) {
    // True-size mock on the #58 screenshot: Fire draws the square at 148x148 at (1564,655) in a 263x148 tile at (1506,655).
    const sq = await draw(square, 148, 148);
    const shot = await sharp(`${PREVIEW}/tv-apps-unfocused.png`).composite([{ input: sq, left: 1564, top: 655 }]).png().toBuffer();
    await sharp(shot).extract({ left: 1498, top: 647, width: 279, height: 164 }).resize(837, null, { kernel: 'nearest' })
      .toFile(`${PREVIEW}/square-d-mock-zoom3x.png`);
    await sharp(await draw(square, 512, 512)).toFile(`${PREVIEW}/square-d-512.png`);
  }
})();
