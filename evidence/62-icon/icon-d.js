// Renders design D (from evidence/58-icon/mock.js) as 16:9 launcher icons and banners.
const sharp = require('sharp');
const RES = process.argv[2];
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
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180">${defs}
  <rect width="320" height="180" fill="${NAVY}"/>
  <text x="160" y="46" text-anchor="middle" font-family="Liberation Serif" font-style="italic" font-size="44" fill="#D6E8FF">Celastrina</text>
  ${moon(160, 116, 54)}</svg>`;
// 16:9 at every density: 320x180 at xhdpi, the Leanback banner size, scaled by density.
const sizes = { mdpi: 160, hdpi: 240, xhdpi: 320, xxhdpi: 480, xxxhdpi: 640 };
(async () => {
  for (const [d, w] of Object.entries(sizes)) {
    const h = (w * 9) / 16;
    const png = await sharp(Buffer.from(svg), { density: 72 * 8 }).resize(w, h, { fit: 'fill', kernel: 'lanczos3' })
      .flatten({ background: NAVY }).png({ compressionLevel: 9 }).toBuffer();
    await sharp(png).toFile(`${RES}/mipmap-${d}/ic_launcher.png`);
    if (d !== 'xxxhdpi') await sharp(png).toFile(`${RES}/drawable-${d}/banner.png`);
    console.log(d, w, h);
  }
})();
