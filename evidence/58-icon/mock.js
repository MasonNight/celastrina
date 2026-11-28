// Builds candidate Celastrina tiles as SVG and composites them onto real Fire TV screenshots.
const sharp = require('sharp');
const fs = require('fs');
const S = process.argv[2]; // scratchpad dir
const OUT = `${S}/out`;
fs.mkdirSync(OUT, { recursive: true });

const NAVY = '#070B18';
const WING = 'M3,-4 C18,-38 58,-64 86,-58 C97,-54 94,-32 82,-15 C66,4 32,7 3,3 Z M3,5 C30,5 62,13 67,35 C70,53 51,64 34,57 C17,50 6,30 3,11 Z';
const BODY = 'M-3.4,4 a3.4,30 0 1,0 6.8,0 a3.4,30 0 1,0 -6.8,0 Z M-4.6,-28 a4.6,4.6 0 1,0 9.2,0 a4.6,4.6 0 1,0 -9.2,0 Z M-20.5,-63 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 Z M14.5,-63 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 Z';
const defs = `<defs><radialGradient id="moon" cx="0.42" cy="0.38" r="0.7">
  <stop offset="0" stop-color="#D6E8FF"/><stop offset="0.6" stop-color="#7AA7F5"/><stop offset="1" stop-color="#3F62C9"/></radialGradient></defs>`;
// Moon of radius r centred at (cx, cy), with the butterfly scaled like the shipped icon (0.63 of a 72 radius moon).
function moon(cx, cy, r) {
  const k = (0.63 * r) / 72;
  return `<circle cx="${cx}" cy="${cy}" r="${r}" fill="url(#moon)"/>
  <g transform="translate(${cx} ${cy + (4.5 * r) / 72}) scale(${k})" fill="${NAVY}">
    <path d="${WING}"/><path d="${WING}" transform="scale(-1 1)"/><path d="${BODY}"/>
    <path d="M-1.5,-31 Q-8,-50 -17,-62 M1.5,-31 Q8,-50 17,-62" stroke="${NAVY}" stroke-width="2" stroke-linecap="round" fill="none"/>
  </g>`;
}
const svg = (w, h, body) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${defs}${body}</svg>`;

// Each design: viewBox units 180x180 (square slot) or 320x180 (whole tile).
const designs = {
  current: { w: 180, h: 180, body: `<rect width="180" height="180" fill="${NAVY}"/>${moon(90, 90, 72)}` },
  'a-serif': {
    w: 180, h: 180,
    body: `<rect width="180" height="180" fill="${NAVY}"/>
      <text x="90" y="44" text-anchor="middle" font-family="Liberation Serif" font-style="italic" font-size="38" fill="#D6E8FF">Celastrina</text>
      ${moon(90, 114, 56)}`,
  },
  'b-caps': {
    w: 180, h: 180,
    body: `<rect width="180" height="180" fill="${NAVY}"/>
      <text x="91" y="40" text-anchor="middle" font-family="DejaVu Sans" font-weight="bold" font-size="20" letter-spacing="2.4" fill="#D6E8FF">CELASTRINA</text>
      ${moon(90, 112, 58)}`,
  },
  'c-grey-blend': {
    w: 180, h: 180,
    body: `<rect width="180" height="180" fill="#5A5A5A"/>
      <text x="90" y="42" text-anchor="middle" font-family="Liberation Serif" font-style="italic" font-weight="bold" font-size="36" fill="#FFFFFF">Celastrina</text>
      ${moon(90, 114, 56)}`,
  },
  'd-wide': {
    w: 320, h: 180,
    body: `<rect width="320" height="180" fill="${NAVY}"/>
      <text x="160" y="46" text-anchor="middle" font-family="Liberation Serif" font-style="italic" font-size="44" fill="#D6E8FF">Celastrina</text>
      ${moon(160, 116, 54)}`,
  },
};

// Unfocused tile in apps2.png (Your Apps & Channels, 1920x1080): 263x147 at (1506,655); Fire draws the square at 148x147, x=1564.
const TILE = { x: 1506, y: 655, w: 263, h: 148 };
const SQ = { x: 1564, y: 655, w: 148, h: 148 };

async function render(name) {
  const d = designs[name];
  const slot = d.w === 180 ? SQ : TILE;
  const png = await sharp(Buffer.from(svg(d.w, d.h, d.body)), { density: 72 * 8 })
    .resize(slot.w, slot.h, { fit: 'fill', kernel: 'lanczos3' }).png().toBuffer();
  const shot = await sharp(`${S}/shots/apps2.png`).composite([{ input: png, left: slot.x, top: slot.y }]).png().toBuffer();
  await sharp(shot).toFile(`${OUT}/${name}-full.png`);
  // 1:1 crop of the bottom two rows: the edited tile (right) beside the untouched current Celastrina tile (bottom left).
  await sharp(shot).extract({ left: 140, top: 490, width: 1640, height: 480 }).toFile(`${OUT}/${name}-crop.png`);
  // 3x zoom of the tile for inspection only (not true size).
  await sharp(shot).extract({ left: TILE.x - 8, top: TILE.y - 8, width: TILE.w + 16, height: TILE.h + 16 })
    .resize((TILE.w + 16) * 3, null, { kernel: 'nearest' }).toFile(`${OUT}/${name}-zoom3x.png`);
}

(async () => {
  for (const n of Object.keys(designs)) await render(n);
  console.log(fs.readdirSync(OUT).join('\n'));
})();

// Focused state, from apps3.png: Fire scales the focused tile so the square slot is 195x195 at (1541,632).
(async () => {
  for (const name of ['a-serif', 'c-grey-blend']) {
    const d = designs[name];
    const png = await sharp(Buffer.from(svg(d.w, d.h, d.body)), { density: 72 * 8 }).resize(195, 195).png().toBuffer();
    await sharp(`${S}/shots/apps3.png`).composite([{ input: png, left: 1541, top: 632 }]).toFile(`${OUT}/${name}-focused-full.png`);
  }
})();
