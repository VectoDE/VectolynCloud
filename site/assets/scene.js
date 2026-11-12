// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Voxel world preview for the hero: a small island with the Vectolyn mark built from blocks.
// site.js loads this module only on capable devices, after the page has loaded and when motion is allowed.
// The pre-rendered picture (assets/img/world.*) underneath stays the fallback; it was made with renderStill().
import * as THREE from './vendor/three/three.module.min.js';

const REST = Math.PI / 4 - 0.3;

const PALETTE = {
  grass: 0x2fb58a, dirt: 0x6b4a3a, stone: 0x6b7088, sand: 0xe5c07b, water: 0x1b7f8c,
  trunk: 0x5a3d2e, leaves: 0x1f8f6a, cyan: 0x00e5ff, gold: 0xffd23f,
};

// Deterministic value noise, so every visitor (and the fallback picture) sees the same island.
function rng(seed) { return () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; }; }
function makeNoise(size, seed) {
  const r = rng(seed);
  const g = [];
  for (let i = 0; i < (size + 1) * (size + 1); i++) g.push(r());
  const at = (x, y) => g[y * (size + 1) + x];
  const smooth = (t) => t * t * (3 - 2 * t);
  return (x, y) => {
    const x0 = Math.floor(x), y0 = Math.floor(y), fx = smooth(x - x0), fy = smooth(y - y0);
    const a = at(x0, y0), b = at(x0 + 1, y0), c = at(x0, y0 + 1), d = at(x0 + 1, y0 + 1);
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
  };
}

function buildWorld() {
  const N = 26, half = N / 2, blocks = [], height = {};
  const n1 = makeNoise(8, 7), n2 = makeNoise(16, 21);
  const add = (type, x, y, z) => blocks.push([type, x, y, z]);
  for (let x = 0; x < N; x++) {
    for (let z = 0; z < N; z++) {
      const dx = (x - half + 0.5) / half, dz = (z - half + 0.5) / half;
      const fall = Math.max(0, 1 - Math.sqrt(dx * dx + dz * dz));
      const h = Math.round((n1((x / N) * 7.99, (z / N) * 7.99) * 0.7 + n2((x / N) * 15.99, (z / N) * 15.99) * 0.3) * 10 * fall + fall * 3) - 1;
      height[x + ',' + z] = h;
      if (fall < 0.2 || h < 0) { if (fall > 0.02) add('water', x, 0, z); continue; }
      for (let y = 0; y <= h; y++) add(y === h ? (h === 0 || fall < 0.3 ? 'sand' : 'grass') : (y < h - 2 ? 'stone' : 'dirt'), x, y, z);
      if (h >= 3 && (x * 7 + z * 13) % 29 === 0 && !(Math.abs(z - half) <= 7 && x >= 4 && x <= 21)) {
        for (let t = 1; t <= 3; t++) add('trunk', x, h + t, z);
        for (let lx = -1; lx <= 1; lx++) for (let lz = -1; lz <= 1; lz++) for (let ly = 3; ly <= 4; ly++) {
          if (ly === 4 && Math.abs(lx) + Math.abs(lz) === 2) continue;
          if (!(lx === 0 && lz === 0 && ly === 3)) add('leaves', x + lx, h + ly, z + lz);
        }
      }
    }
  }
  // The Vectolyn mark as a block sculpture: rows of the 8 x 8 brand mark, top row first.
  const mark = ['CC....CC', '.CC..CC.', '.CC..CC.', '..CCCC..', '...CC...', '........', '...GG...', '...GG...'];
  let ground = 0;
  for (let j = 0; j < 8; j++) ground = Math.max(ground, height[(9 + j) + ',' + half] ?? 0);
  mark.forEach((row, i) => [...row].forEach((ch, j) => { if (ch !== '.') add(ch === 'C' ? 'cyan' : 'gold', 9 + j, ground + 1 + (7 - i), half); }));
  return { blocks, N };
}

function makeScene(width, height) {
  const scene = new THREE.Scene();
  const { blocks, N } = buildWorld();
  const groups = {};
  blocks.forEach((b) => { (groups[b[0]] ||= []).push(b); });
  const geo = new THREE.BoxGeometry(1, 1, 1);
  const world = new THREE.Group();
  const m = new THREE.Matrix4();
  for (const [type, list] of Object.entries(groups)) {
    const glow = type === 'cyan' || type === 'gold';
    const mat = new THREE.MeshLambertMaterial({
      color: PALETTE[type], transparent: type === 'water', opacity: type === 'water' ? 0.78 : 1,
      emissive: glow ? PALETTE[type] : 0x000000, emissiveIntensity: glow ? 0.35 : 0,
    });
    const mesh = new THREE.InstancedMesh(geo, mat, list.length);
    list.forEach(([, x, y, z], i) => { m.makeTranslation(x - N / 2, y, z - N / 2); mesh.setMatrixAt(i, m); });
    world.add(mesh);
  }
  scene.add(world);
  scene.add(new THREE.HemisphereLight(0xcfe9ff, 0x2a2440, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 2.4);
  sun.position.set(12, 24, 8);
  scene.add(sun);
  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 200);
  camera.position.set(30, 26, 30);
  camera.lookAt(0, 6, 0);
  const fit = (w, h) => {
    const aspect = w / h, view = 15;
    camera.left = -view * aspect; camera.right = view * aspect; camera.top = view; camera.bottom = -view;
    camera.updateProjectionMatrix();
  };
  fit(width, height);
  world.rotation.y = REST;
  return { scene, camera, world, fit };
}

/** Renders one frame into a canvas; used to make the static fallback picture. */
export function renderStill(canvas, width, height) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1);
  renderer.setSize(width, height, false);
  const { scene, camera } = makeScene(width, height);
  renderer.render(scene, camera);
}

export function start(figure, controls) {
  const stage = figure.querySelector('.world-stage');
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  const canvas = renderer.domElement;
  canvas.setAttribute('aria-hidden', 'true');
  const { scene, camera, world, fit } = makeScene(stage.clientWidth, stage.clientHeight);

  let running = false, visible = true, raf = 0, last = performance.now(), time = 0;
  const frame = (now) => {
    time += Math.max(0, Math.min(now - last, 100)) / 1000;
    last = now;
    world.rotation.y = REST + Math.sin(time * 0.25) * 0.6;
    renderer.render(scene, camera);
    if (running && visible) raf = requestAnimationFrame(frame);
  };
  const kick = () => { if (running && visible) { last = performance.now(); cancelAnimationFrame(raf); raf = requestAnimationFrame(frame); } };
  const resize = () => { renderer.setSize(stage.clientWidth, stage.clientHeight, false); fit(stage.clientWidth, stage.clientHeight); renderer.render(scene, camera); };

  new ResizeObserver(resize).observe(stage);
  new IntersectionObserver((e) => { visible = e[0].isIntersecting; kick(); }).observe(stage);
  document.addEventListener('visibilitychange', () => { visible = !document.hidden; kick(); });

  resize();
  stage.appendChild(canvas);
  renderer.render(scene, camera);
  figure.classList.add('has-3d');
  controls.onChange((paused) => { running = !paused; if (running) kick(); else cancelAnimationFrame(raf); });
  running = !controls.paused();
  kick();
}
