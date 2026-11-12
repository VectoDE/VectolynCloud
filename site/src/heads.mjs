// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Minecraft heads for the team page, fetched at build time so visitors never contact a third party.
// Name -> UUID (api.mojang.com), UUID -> skin URL (sessionserver.mojang.com), skin -> the six faces of the
// head with the hat layer on top, saved as one 48 x 8 PNG strip (front, right, back, left, top, bottom).
// Only this crop is stored, never the whole skin. Results are cached in heads.json next to this file;
// a failed lookup keeps the last good crop, and without one the page shows the placeholder cube.

import { inflateSync, deflateSync } from 'node:zlib';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const NAME = /^[A-Za-z0-9_]{3,16}$/;
const MAX_AGE = 24 * 60 * 60 * 1000; // refresh at most once a day per name
const GAP = 400; // ms between Mojang requests, well inside the published rate limits
const UA = { 'user-agent': 'vectolyn-site-build' };

// [x, y] of each face of the head on a skin; the hat layer sits 32 px to the right.
const FACES = [[8, 8], [0, 8], [24, 8], [16, 8], [8, 0], [16, 0]];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url) {
  const res = await fetch(url, { headers: UA, signal: AbortSignal.timeout(6000) });
  if (res.status === 204 || res.status === 404) return null; // unknown name
  if (!res.ok) throw new Error(`${new URL(url).host} answered ${res.status}`);
  return res.json();
}

// --- minimal PNG reader and writer (8-bit RGB/RGBA, not interlaced: what Mojang serves) ---
const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };

export function decodePng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a PNG');
  let pos = 8, w, h, type, idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4); type = data[9];
      if (data[8] !== 8 || data[12] !== 0 || (type !== 6 && type !== 2)) throw new Error('unsupported PNG format');
    } else if (kind === 'IDAT') idat.push(data);
    else if (kind === 'IEND') break;
    pos += 12 + len;
  }
  if (w !== 64 || (h !== 64 && h !== 32)) throw new Error(`unexpected skin size ${w}x${h}`);
  const bpp = type === 6 ? 4 : 3, stride = w * bpp, raw = inflateSync(Buffer.concat(idat));
  const px = Buffer.alloc(w * h * 4);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)], line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? line[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
      const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      line[i] = (line[i] + [0, a, b, (a + b) >> 1, pa <= pb && pa <= pc ? a : pb <= pc ? b : c][f]) & 0xff;
    }
    for (let x = 0; x < w; x++) for (let k = 0; k < 4; k++) px[(y * w + x) * 4 + k] = k < bpp ? line[x * bpp + k] : 255;
    prev = line;
  }
  return { w, h, px };
}

export function encodePng(w, h, px) {
  const chunk = (kind, data) => { const out = Buffer.alloc(12 + data.length); out.writeUInt32BE(data.length, 0); out.write(kind, 4, 'latin1'); data.copy(out, 8); out.writeUInt32BE(crc(out.subarray(4, 8 + data.length)), 8 + data.length); return out; };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  const raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) px.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

/** The six head faces with the hat layer blended on top, as a 48 x 8 RGBA strip. */
export function headStrip(skin) {
  const out = Buffer.alloc(48 * 8 * 4);
  const at = (x, y) => (y * skin.w + x) * 4;
  FACES.forEach(([fx, fy], i) => {
    for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) {
      const o = (y * 48 + i * 8 + x) * 4, b = at(fx + x, fy + y), t = at(fx + 32 + x, fy + y);
      const alpha = skin.px[t + 3] / 255;
      for (let k = 0; k < 3; k++) out[o + k] = Math.round(skin.px[t + k] * alpha + skin.px[b + k] * (1 - alpha));
      out[o + 3] = 255; // the base head layer is opaque in the game
    }
  });
  return out;
}

async function lookup(name) {
  const profile = await getJson(`https://api.mojang.com/users/profiles/minecraft/${name}`);
  if (!profile) return { missing: true };
  await sleep(GAP);
  const session = await getJson(`https://sessionserver.mojang.com/session/minecraft/profile/${profile.id}`);
  const tex = session?.properties?.find((p) => p.name === 'textures');
  if (!tex) throw new Error('profile without textures');
  const url = JSON.parse(Buffer.from(tex.value, 'base64').toString('utf8'))?.textures?.SKIN?.url;
  if (!url) return { uuid: profile.id, name: profile.name, noSkin: true };
  const skinUrl = new URL(url.replace(/^http:/, 'https:'));
  if (skinUrl.hostname !== 'textures.minecraft.net') throw new Error('skin from an unexpected host');
  await sleep(GAP);
  const res = await fetch(skinUrl, { headers: UA, signal: AbortSignal.timeout(6000) });
  if (!res.ok) throw new Error(`textures.minecraft.net answered ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length > 64 * 1024) throw new Error('skin file too large');
  return { uuid: profile.id, name: profile.name, skinId: skinUrl.pathname.split('/').pop(), png: encodePng(48, 8, headStrip(decodePng(buf))) };
}

/**
 * Resolves every Minecraft name to a head file under site/assets/img/team/.
 * Returns { [name]: { file, uuid } } for the names that have a usable head; the rest get the placeholder.
 */
export async function resolveHeads(names, { siteDir, offline }) {
  const cacheFile = join(siteDir, 'src', 'heads.json');
  const dir = join(siteDir, 'assets', 'img', 'team');
  const cache = existsSync(cacheFile) ? JSON.parse(readFileSync(cacheFile, 'utf8')) : {};
  const result = {};
  let changed = false;
  for (const name of names) {
    if (!NAME.test(name)) { console.warn(`team: "${name}" is not a valid Minecraft name, using the placeholder`); continue; }
    const key = name.toLowerCase(), file = `assets/img/team/${key}.png`, entry = cache[key];
    const fresh = entry && Date.now() - Date.parse(entry.checked) < MAX_AGE;
    if (!offline && !fresh) {
      try {
        const r = await lookup(name);
        if (r.missing) { console.warn(`team: Minecraft name "${name}" does not exist, using the placeholder`); delete cache[key]; changed = true; continue; }
        if (r.png) { mkdirSync(dir, { recursive: true }); writeFileSync(join(siteDir, file), r.png); }
        cache[key] = { name: r.name, uuid: r.uuid, skin: r.skinId || null, checked: new Date().toISOString() };
        changed = true;
        await sleep(GAP);
      } catch (e) {
        console.warn(`team: could not fetch the head of "${name}" (${e.message}); keeping the cached one if there is one`);
      }
    }
    if (cache[key]?.skin && existsSync(join(siteDir, file))) result[name] = { key, file, uuid: cache[key].uuid, name: cache[key].name };
  }
  if (changed) writeFileSync(cacheFile, JSON.stringify(cache, null, 2) + '\n');
  return result;
}
